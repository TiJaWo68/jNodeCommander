package de.in.jnc.connection.browser.backend;

/**
 * Functional interface for handling SSL certificate errors in a
 * backend-agnostic way.
 * <p>
 * Each browser backend detects certificate errors differently:
 * <ul>
 *   <li><b>JavaFX WebView:</b> via {@code Worker.State.FAILED} +
 *       {@code HttpsURLConnection} retry through {@code CertificateTrustManager}</li>
 *   <li><b>JCEF:</b> via {@code CefLoadHandler.onCertificateError()}</li>
 * </ul>
 * This interface unifies both paths so that the same
 * {@link de.in.jnc.connection.browser.CertificateWarningDialog} logic can
 * be reused.
 */
@FunctionalInterface
public interface CertificateErrorHandler {

    /**
     * Called when a backend detects an untrusted or invalid server certificate.
     *
     * @param certError  a human-readable description of the error type
     *                   (e.g. {@code "CERT_AUTHORITY_INVALID"},
     *                   {@code "SSL handshake failed"})
     * @param requestUrl the URL that triggered the error
     * @param sslInfo    certificate details from the backend, or {@code null}
     *                   if not available
     * @return {@code true} if the certificate should be accepted,
     *         {@code false} to reject it
     */
    boolean onCertificateError(String certError, String requestUrl, SslCertInfo sslInfo);
}
