package de.in.jnc.connection.browser;

import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A custom {@link X509TrustManager} that first delegates to the JVM's default
 * trust manager. If the default rejects the certificate, the user is prompted
 * via {@link CertificateWarningDialog} whether to accept it anyway.
 * <p>
 * Accepted certificates are cached for the duration of the JVM session so the
 * user is only asked once per unique certificate.
 * <p>
 * Thread-safe: uses {@link ConcurrentHashMap} for the accepted-certificate
 * cache and synchronisation via {@link java.util.concurrent.CountDownLatch}
 * for the UI dialog.
 */
public class CertificateTrustManager implements X509TrustManager {

    private static final Logger LOGGER = LogManager.getLogger(CertificateTrustManager.class);

    /** The default JVM trust manager we delegate to first. */
    private final X509TrustManager defaultTrustManager;

    /**
     * In-memory cache of accepted certificate encodings (SHA-256 hashes).
     * Once a user accepts a certificate, it is stored here for the session.
     */
    private static final Set<String> acceptedCertificates = ConcurrentHashMap.newKeySet();

    /**
     * Optional target URL for context in the warning dialog.
     * Set per-connection by the caller if available.
     */
    private static final ThreadLocal<String> pendingTargetUrl = new ThreadLocal<>();

    /**
     * Sets the target URL for the current thread's SSL handshake context.
     * Called before an HTTPS connection attempt so the dialog can show which
     * server is being connected to.
     */
    public static void setTargetUrl(String url) {
        pendingTargetUrl.set(url);
    }

    /**
     * Creates a {@link CertificateTrustManager} wrapping the default JVM trust
     * manager.
     *
     * @throws Exception if the default trust manager cannot be loaded
     */
    public CertificateTrustManager() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        this.defaultTrustManager = findDefaultX509TrustManager(tmf.getTrustManagers());
    }

    private static X509TrustManager findDefaultX509TrustManager(TrustManager[] managers) {
        for (TrustManager tm : managers) {
            if (tm instanceof X509TrustManager) {
                return (X509TrustManager) tm;
            }
        }
        throw new IllegalStateException("No default X509TrustManager found");
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        // Client certificates are not validated in this application
        defaultTrustManager.checkClientTrusted(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        try {
            // First, try the default JVM trust manager
            defaultTrustManager.checkServerTrusted(chain, authType);
        } catch (CertificateException e) {
            // Default trust manager rejected the certificate.
            // Check if the user has already accepted this certificate in this session.
            if (isAlreadyAccepted(chain)) {
                LOGGER.info("Certificate accepted (user previously approved): {}",
                        chain[0].getSubjectDN());
                return;
            }

            // Prompt the user
            String url = pendingTargetUrl.get();
            boolean accepted = CertificateWarningDialog.prompt(
                    "jNodeCommander", chain, url);

            if (accepted) {
                rememberAccepted(chain);
                LOGGER.info("Certificate accepted by user: {}", chain[0].getSubjectDN());

                // Also import into the JDK trust store and Windows certificate store.
                // This is essential for JavaFX WebView: its native HTTP/2 SSL stack
                // (Schannel on Windows) bypasses the JDK SSL layer entirely, so even
                // though our custom TrustManager accepted the cert for JDK-level
                // connections, WebView would still reject it. By importing into the
                // Windows cert store via certutil, WebView's subsequent loads will
                // trust the certificate.
                CertificateStoreManager.importAcceptedCertificate(
                        chain[0], extractHostname(url));
                return;
            } else {
                LOGGER.warn("Certificate rejected by user: {}", chain[0].getSubjectDN());
                throw e;
            }
        }
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return defaultTrustManager.getAcceptedIssuers();
    }

    // ── Session cache ─────────────────────────────────────────────────

    private static boolean isAlreadyAccepted(X509Certificate[] chain) {
        if (chain == null || chain.length == 0) {
            return false;
        }
        return acceptedCertificates.contains(fingerprint(chain[0]));
    }

    private static void rememberAccepted(X509Certificate[] chain) {
        if (chain != null && chain.length > 0) {
            acceptedCertificates.add(fingerprint(chain[0]));
        }
    }

    /**
     * Extracts the hostname from a URL string.
     */
    private static String extractHostname(String url) {
        if (url == null || url.isEmpty()) {
            return "unknown";
        }
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception e) {
            // Simple fallback: remove protocol prefix
            String stripped = url.replaceFirst("^https?://", "");
            int slashIdx = stripped.indexOf('/');
            if (slashIdx > 0) {
                stripped = stripped.substring(0, slashIdx);
            }
            int atIdx = stripped.indexOf('@');
            return atIdx > 0 ? stripped.substring(atIdx + 1) : stripped;
        }
    }

    /**
     * Computes a unique fingerprint (SHA-256 hex) for a certificate.
     */
    private static String fingerprint(X509Certificate cert) {
        try {
            java.security.MessageDigest md =
                    java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(cert.getEncoded());
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // Fallback: use the encoded form's identity hash
            return Integer.toHexString(System.identityHashCode(cert));
        }
    }

    // ── Installer ─────────────────────────────────────────────────────

    /**
     * Installs the {@link CertificateTrustManager} as the default SSL context
     * and hostname verifier for the entire JVM.
     * <p>
     * Also sets {@code com.sun.webkit.useJVMSSLSocket=true} so that JavaFX
     * WebView (when using JDK SSL sockets) picks up this trust manager.
     */
    public static void installAsDefault() {
        try {
            CertificateTrustManager ctm = new CertificateTrustManager();
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{ctm}, new java.security.SecureRandom());
            SSLContext.setDefault(sc);

            // Also set on HttpsURLConnection as a fallback
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(
                    sc.getSocketFactory());
            javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier(
                    new PromptingHostnameVerifier());
        } catch (Exception e) {
            LOGGER.error("Failed to install CertificateTrustManager", e);
        }
    }

    /**
     * A {@link HostnameVerifier} that first tries the default verifier and
     * prompts the user on failure.
     */
    private static class PromptingHostnameVerifier implements HostnameVerifier {

        private static final Set<String> acceptedHostnames = ConcurrentHashMap.newKeySet();

        @Override
        public boolean verify(String hostname, SSLSession session) {
            // First try the default verifier
            javax.net.ssl.HostnameVerifier defaultVerifier =
                    javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier();
            if (defaultVerifier != null && defaultVerifier.verify(hostname, session)) {
                return true;
            }

            // Check cache
            String key = hostname + "|" + session.getPeerHost();
            if (acceptedHostnames.contains(key)) {
                return true;
            }

            // Prompt user via certificate dialog
            try {
                java.security.cert.Certificate[] certs = session.getPeerCertificates();
                X509Certificate[] chain = Arrays.copyOf(certs, certs.length,
                        X509Certificate[].class);
                String url = pendingTargetUrl.get();
                boolean accepted = CertificateWarningDialog.prompt(
                        "jNodeCommander", chain,
                        url != null ? url : "https://" + hostname);
                if (accepted) {
                    acceptedHostnames.add(key);
                    return true;
                }
            } catch (Exception e) {
                LOGGER.warn("Hostname verification prompt failed", e);
            }
            return false;
        }
    }

}
