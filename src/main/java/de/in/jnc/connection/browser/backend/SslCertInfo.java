package de.in.jnc.connection.browser.backend;

import java.security.cert.X509Certificate;

/**
 * Backend-agnostic container for SSL certificate information.
 * <p>
 * Used by {@link CertificateErrorHandler} to provide certificate details
 * to the user regardless of which browser backend detected the error.
 */
public class SslCertInfo {

    private final X509Certificate[] chain;
    private final String hostname;

    /**
     * Creates a new SslCertInfo.
     *
     * @param chain    the certificate chain presented by the server
     * @param hostname the hostname that was being connected to
     */
    public SslCertInfo(X509Certificate[] chain, String hostname) {
        this.chain = chain != null ? chain.clone() : new X509Certificate[0];
        this.hostname = hostname;
    }

    /**
     * Returns the server's certificate chain.
     */
    public X509Certificate[] getChain() {
        return chain.clone();
    }

    /**
     * Returns the leaf (end-entity) certificate, or {@code null} if the
     * chain is empty.
     */
    public X509Certificate getLeafCertificate() {
        return chain.length > 0 ? chain[0] : null;
    }

    /**
     * Returns the hostname that was being connected to.
     */
    public String getHostname() {
        return hostname;
    }
}
