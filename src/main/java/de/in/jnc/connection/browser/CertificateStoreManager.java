package de.in.jnc.connection.browser;

import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Manages importing SSL/TLS certificates into:
 * <ul>
 *   <li>The JDK custom trust store (for JDK-level SSL connections)</li>
 *   <li>The Windows certificate store via {@code certutil} (for JavaFX WebView's
 *       native Schannel SSL stack)</li>
 * </ul>
 * <p>
 * WebView on Windows uses Schannel (Windows' native SSL) for HTTP/2 connections.
 * The JDK-level trust manager ({@link CertificateTrustManager}) is not consulted
 * for these native SSL handshakes. By importing certificates into the Windows
 * "Current User\Trusted Root Certification Authorities" store, we make Schannel
 * trust them, which allows WebView to load pages with self-signed / internal-CA
 * certificates.
 * <p>
 * Certificates placed in {@code ~/.jnc/certs/*.pem} are automatically imported
 * at application startup.
 */
public final class CertificateStoreManager {

    private static final Logger LOGGER = LogManager.getLogger(CertificateStoreManager.class);

    /** Directory where users place PEM certificate files for automatic import. */
    static final Path CERTS_DIR = Paths.get(
            System.getProperty("user.home"), ".jnc", "certs");

    /** PKCS12 trust store path (JDK-level). */
    private static final Path TRUST_STORE_PATH = Paths.get(
            System.getProperty("user.home"), ".jnc", "truststore.p12");

    /** Trust store password (hard-coded is acceptable for a local trust store). */
    private static final char[] TRUST_STORE_PASSWORD = "jnc-truststore".toCharArray();

    /** Set of certificate fingerprints already imported (to avoid duplicates). */
    private static final Set<String> importedFingerprints = ConcurrentHashMap.newKeySet();

    static {
        loadCertificatesFromDirectory();
    }

    private CertificateStoreManager() {
        // utility class
    }

    // ── Directory import ─────────────────────────────────────────────────

    /**
     * Scans {@link #CERTS_DIR} for {@code .pem}, {@code .crt}, {@code .cer}
     * files and imports each one into the JDK trust store and the Windows
     * certificate store.
     */
    public static void loadCertificatesFromDirectory() {
        try {
            Files.createDirectories(CERTS_DIR);
        } catch (Exception e) {
            LOGGER.warn("Cannot create certs directory: {}", CERTS_DIR, e);
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(CERTS_DIR, "*.{pem,crt,cer}")) {
            for (Path certFile : stream) {
                try {
                    importCertificateFromFile(certFile);
                } catch (Exception e) {
                    LOGGER.warn("Failed to import certificate from {}", certFile, e);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to scan certificates directory", e);
        }
    }

    /**
     * Reads a PEM file, extracts the first {@link X509Certificate}, and imports
     * it into both the JDK trust store and the Windows certificate store.
     */
    public static boolean importCertificateFromFile(Path pemFile) {
        try {
            byte[] pemBytes = Files.readAllBytes(pemFile);
            X509Certificate cert = parsePemCertificate(pemBytes);
            if (cert == null) {
                LOGGER.warn("No X.509 certificate found in {}", pemFile);
                return false;
            }

            String fingerprint = fingerprint(cert);
            if (importedFingerprints.contains(fingerprint)) {
                LOGGER.debug("Certificate already imported, skipping: {}",
                        cert.getSubjectX500Principal());
                return true;
            }

            // Import into JDK trust store
            importIntoJdkTrustStore(cert, fingerprint);

            // Import into Windows certificate store (affects WebView)
            importIntoWindowsStore(pemFile);

            importedFingerprints.add(fingerprint);
            LOGGER.info("Imported certificate: {} (SHA-256: {}...)",
                    cert.getSubjectX500Principal(),
                    fingerprint.substring(0, 16));
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to import certificate from {}", pemFile, e);
            return false;
        }
    }

    // ── Programmatic import (from accepted dialog) ───────────────────────

    /**
     * Saves an {@link X509Certificate} as a PEM file in the certs directory,
     * then imports it into both the JDK trust store and the Windows certificate
     * store.
     * <p>
     * This is called when a user accepts a certificate via
     * {@link CertificateWarningDialog}.
     *
     * @param cert    the accepted server certificate
     * @param hostname the hostname the certificate was accepted for (used in filename)
     * @return {@code true} if import was successful
     */
    public static boolean importAcceptedCertificate(X509Certificate cert, String hostname) {
        try {
            String fingerprint = fingerprint(cert);
            if (importedFingerprints.contains(fingerprint)) {
                LOGGER.debug("Certificate already imported, skipping");
                return true;
            }

            Files.createDirectories(CERTS_DIR);

            // Save as PEM file in certs directory
            String safeHostname = hostname != null
                    ? hostname.replaceAll("[^a-zA-Z0-9.-]", "_")
                    : "unknown";
            Path pemFile = CERTS_DIR.resolve("accepted-" + safeHostname + ".pem");
            saveAsPem(cert, pemFile);

            // Import into JDK trust store (always succeeds or throws)
            importIntoJdkTrustStore(cert, fingerprint);
            LOGGER.info("Certificate imported into JDK trust store for host '{}': {}",
                    hostname, cert.getSubjectX500Principal());

            // Import into Windows certificate store (best-effort – may fail
            // for non-root certificates, which is expected).
            boolean winOk = importIntoWindowsStore(pemFile);
            if (winOk) {
                LOGGER.info("Certificate also imported into Windows certificate store");
            } else {
                LOGGER.warn("Could not import certificate into Windows store – "
                        + "this is expected for non-root certificates. "
                        + "WebView may still prompt for this certificate.");
            }

            importedFingerprints.add(fingerprint);
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to import accepted certificate for host '{}'", hostname, e);
            return false;
        }
    }

    // ── JDK trust store ──────────────────────────────────────────────────

    /**
     * Imports a certificate into the JDK-level PKCS12 trust store and reloads
     * the default SSL context so that JDK SSL connections (e.g.
     * {@link javax.net.ssl.HttpsURLConnection}) trust this certificate.
     */
    /**
     * Imports a certificate into the JDK-level PKCS12 trust store.
     * <p>
     * This does <b>not</b> replace the default {@link SSLContext} because
     * the {@link CertificateTrustManager} wrapper must stay in place to
     * handle future certificate prompts.  The trust store file is persisted
     * so it survives application restarts, and the
     * {@link CertificateTrustManager} can be extended to consult it.
     */
    private static void importIntoJdkTrustStore(X509Certificate cert, String alias)
            throws Exception {
        KeyStore trustStore = loadOrCreateTrustStore();
        trustStore.setCertificateEntry(alias, cert);

        try (OutputStream out = Files.newOutputStream(TRUST_STORE_PATH)) {
            trustStore.store(out, TRUST_STORE_PASSWORD);
        }
    }

    private static KeyStore loadOrCreateTrustStore() throws Exception {
        if (Files.exists(TRUST_STORE_PATH)) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (java.io.InputStream in = Files.newInputStream(TRUST_STORE_PATH)) {
                ks.load(in, TRUST_STORE_PASSWORD);
            }
            return ks;
        }
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, TRUST_STORE_PASSWORD);
        return ks;
    }

    // ── Windows certificate store (Schannel) ─────────────────────────────

    /**
     * Imports a PEM certificate file into the Windows Current User's Trusted
     * Root store using {@code certutil.exe}.
     * <p>
     * This is the critical step for JavaFX WebView: on Windows, WebView's native
     * SSL stack (Schannel) reads from the Windows certificate store. Once the
     * certificate is imported here, WebView will trust it even for HTTP/2
     * connections that bypass the JDK SSL layer entirely.
     * <p>
     * The {@code -user} flag limits the import to the current user's store and
     * does <b>not</b> require administrator privileges.
     */
    static boolean importIntoWindowsStore(Path pemFile) {
        // Try certutil first (works for CA certificates)
        if (tryCertutilImport(pemFile)) {
            return true;
        }

        // Fallback: PowerShell Import-Certificate (handles self-signed server
        // certificates that certutil rejects as "not a root certificate").
        LOGGER.info("certutil failed – attempting PowerShell Import-Certificate fallback...");
        return tryPowerShellImport(pemFile);
    }

    /**
     * Attempts to import a PEM certificate into the Windows Current User's
     * Trusted Root store using {@code certutil.exe}.
     * <p>
     * Works for CA certificates but fails for self-signed server certificates
     * with {@code ERROR_INVALID_DATA} ("Ein Zertifikat, das keine
     * Stammzertifikat ist, kann nicht einem Stammspeicher hinzugefügt werden").
     */
    private static boolean tryCertutilImport(Path pemFile) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "certutil.exe", "-user", "-addstore", "Root",
                    pemFile.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                LOGGER.info("Successfully imported certificate into Windows store via certutil: {}",
                        pemFile.getFileName());
                return true;
            } else {
                LOGGER.warn("certutil exited with code {}:\n{}", exitCode, output);
                return false;
            }
        } catch (Exception e) {
            LOGGER.error("Failed to import certificate into Windows store via certutil", e);
            return false;
        }
    }

    /**
     * Attempts to import a PEM certificate into the Windows Current User's
     * Trusted Root store using the .NET {@code X509Store} API via PowerShell.
     * <p>
     * Unlike {@code certutil}, this approach does <b>not</b> enforce CA basic
     * constraint checks, so self-signed server certificates can be imported
     * into the Root store. This is the critical step for JavaFX WebView's
     * native Schannel stack to trust the certificate.
     * <p>
     * Unlike the {@code Import-Certificate} cmdlet, the .NET {@code X509Store}
     * API works <b>without</b> interactive UI prompts, making it suitable for
     * non-interactive processes.
     */
    private static boolean tryPowerShellImport(Path pemFile) {
        Path derFile = null;
        try {
            // Step 1: Parse PEM and write DER-encoded bytes to a temporary file.
            // .NET Framework's X509Certificate2(string) constructor does NOT
            // support PEM format, so we must convert to DER first.
            byte[] pemBytes = Files.readAllBytes(pemFile);
            X509Certificate cert = parsePemCertificate(pemBytes);
            derFile = Files.createTempFile("jnc-cert-", ".cer");
            Files.write(derFile, cert.getEncoded());

            // Step 2: Use PowerShell with .NET X509Store API to import the
            // DER-encoded certificate without interactive UI prompts.
            String derPath = derFile.toAbsolutePath().toString()
                    .replace("'", "''"); // escape single quotes for PowerShell
            String command =
                    "$cert = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2('" +
                            derPath + "'); " +
                            "$store = New-Object System.Security.Cryptography.X509Certificates.X509Store(" +
                            "'Root', 'CurrentUser'); " +
                            "$store.Open('ReadWrite'); " +
                            "$store.Add($cert); " +
                            "$store.Close(); " +
                            "Write-Host 'SUCCESS'";

            ProcessBuilder pb = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive",
                    "-Command", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0 && output.toString().contains("SUCCESS")) {
                LOGGER.info("Successfully imported certificate into Windows store via PowerShell .NET API: {}",
                        pemFile.getFileName());
                return true;
            } else {
                LOGGER.warn("PowerShell .NET X509Store import exited with code {}:\n{}",
                        exitCode, output);
                return false;
            }
        } catch (Exception e) {
            LOGGER.error("Failed to import certificate into Windows store via PowerShell .NET API", e);
            return false;
        } finally {
            if (derFile != null) {
                try {
                    Files.deleteIfExists(derFile);
                } catch (IOException e) {
                    LOGGER.warn("Failed to delete temporary DER file: {}", derFile, e);
                }
            }
        }
    }

    // ── PEM handling ─────────────────────────────────────────────────────

    /**
     * Parses the first X.509 certificate from a PEM-encoded byte array.
     */
    static X509Certificate parsePemCertificate(byte[] pemBytes) throws Exception {
        String pem = new String(pemBytes, StandardCharsets.US_ASCII);
        String begin = "-----BEGIN CERTIFICATE-----";
        String end = "-----END CERTIFICATE-----";

        int startIdx = pem.indexOf(begin);
        if (startIdx < 0) {
            return null;
        }
        int endIdx = pem.indexOf(end, startIdx + begin.length());
        if (endIdx < 0) {
            return null;
        }

        String b64 = pem.substring(startIdx + begin.length(), endIdx)
                .replaceAll("\\s+", "");
        byte[] der = Base64.getDecoder().decode(b64);

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
    }

    /**
     * Writes an {@link X509Certificate} in PEM format to the given file.
     */
    static void saveAsPem(X509Certificate cert, Path pemFile) throws Exception {
        byte[] der = cert.getEncoded();
        String b64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der);

        StringBuilder pem = new StringBuilder();
        pem.append("-----BEGIN CERTIFICATE-----\n");
        pem.append(b64);
        pem.append("\n-----END CERTIFICATE-----\n");

        Path tmpFile = pemFile.resolveSibling(pemFile.getFileName() + ".tmp");
        Files.write(tmpFile, pem.toString().getBytes(StandardCharsets.US_ASCII));
        Files.move(tmpFile, pemFile, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    // ── Fingerprint ──────────────────────────────────────────────────────

    /**
     * Computes the SHA-256 hex fingerprint of a certificate.
     */
    static String fingerprint(X509Certificate cert) {
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
            return Integer.toHexString(System.identityHashCode(cert));
        }
    }
}
