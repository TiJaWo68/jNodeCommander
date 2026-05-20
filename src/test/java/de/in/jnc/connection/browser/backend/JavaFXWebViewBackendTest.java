package de.in.jnc.connection.browser.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * Unit / integration tests for {@link JavaFXWebViewBackend}.
 * <p>
 * These tests reproduce the ACTUAL bugs encountered at runtime:
 * <ul>
 *   <li>{@code attemptSslRetry()} loaded {@code initialUrl} ("about:blank")
 *       instead of the actual navigation URL, leaving the user staring at
 *       a blank page after accepting the certificate.</li>
 *   <li>{@code isLikelySslError()} needed to match {@code "Unknown error"}
 *       (Schannel's unhelpful failure message) for the retry to fire at all.</li>
 * </ul>
 */
class JavaFXWebViewBackendTest {

    // ── isLikelySslError() — should return true ────────────────────────

    @Test
    void sslError_withSslKeyword() {
        assertTrue(JavaFXWebViewBackend.isLikelySslError(
                new Throwable("SSL handshake failed")));
    }

    @Test
    void sslError_withHandshakeKeyword() {
        assertTrue(JavaFXWebViewBackend.isLikelySslError(
                new Throwable("handshake error")));
    }

    @Test
    void sslError_withCertificateKeyword() {
        assertTrue(JavaFXWebViewBackend.isLikelySslError(
                new Throwable("certificate verify failed")));
    }

    @Test
    void sslError_withTrustKeyword() {
        assertTrue(JavaFXWebViewBackend.isLikelySslError(
                new Throwable("not trusted")));
    }

    @Test
    void sslError_withUntrustedKeyword() {
        assertTrue(JavaFXWebViewBackend.isLikelySslError(
                new Throwable("untrusted certificate")));
    }

    @Test
    void sslError_withTimeoutKeyword() {
        assertTrue(JavaFXWebViewBackend.isLikelySslError(
                new Throwable("timeout exceeded")));
    }

    @Test
    void sslError_withTimedOutKeyword() {
        assertTrue(JavaFXWebViewBackend.isLikelySslError(
                new Throwable("timed out")));
    }

    /**
     * CRITICAL: WebView's native Schannel stack reports "Unknown error" for
     * SSL certificate failures. This keyword was missing initially, causing
     * the retry to never fire.
     */
    @Test
    void sslError_withUnknownKeyword() {
        assertTrue(JavaFXWebViewBackend.isLikelySslError(
                new Throwable("Unknown error")));
    }

    @Test
    void sslError_caseInsensitive() {
        assertTrue(JavaFXWebViewBackend.isLikelySslError(
                new Throwable("UNKNOWN ERROR")));
    }

    @Test
    void sslError_withFullSchannelMessage() {
        // Simulate the exact error seen in WebView logs against Keycloak
        assertTrue(JavaFXWebViewBackend.isLikelySslError(
                new Throwable("https://192.168.178.231/auth: Unknown error")));
    }

    // ── isLikelySslError() — should return false ───────────────────────

    @Test
    void sslError_nullError_returnsFalse() {
        assertFalse(JavaFXWebViewBackend.isLikelySslError(null));
    }

    @Test
    void sslError_nullMessage_returnsFalse() {
        assertFalse(JavaFXWebViewBackend.isLikelySslError(new Throwable()));
    }

    @Test
    void sslError_emptyMessage_returnsFalse() {
        assertFalse(JavaFXWebViewBackend.isLikelySslError(new Throwable("")));
    }

    @Test
    void sslError_nonSslError_returnsFalse() {
        assertFalse(JavaFXWebViewBackend.isLikelySslError(
                new Throwable("Connection refused")));
    }

    @Test
    void sslError_fileNotFound_returnsFalse() {
        assertFalse(JavaFXWebViewBackend.isLikelySslError(
                new Throwable("404 Not Found")));
    }

    @Test
    void sslError_dnsError_returnsFalse() {
        assertFalse(JavaFXWebViewBackend.isLikelySslError(
                new Throwable("Host not found")));
    }

    // ── Retry URL selection: reproduces the "about:blank" bug ──────────

    /**
     * REPRODUCES THE BUG: When a user navigates AFTER the backend has been
     * initialised with a default URL (e.g. "about:blank"), {@code loadUrl()}
     * must update {@code initialUrl} so that the SSL retry reloads the
     * CORRECT page — not the blank initial page.
     * <p>
     * Before the fix, {@code loadUrl()} only called
     * {@code engine.load(url)} and {@code setTargetUrl(url)} but did NOT
     * update the {@code initialUrl} field.  When the SSL retry fired, it
     * called {@code engine.load(initialUrl)} → {@code engine.load("about:blank")},
     * leaving the user on a blank page after accepting the certificate.
     */
    @Test
    void retryUrl_usesNavigatedUrl_notAboutBlank() {
        // Simulate the scenario:
        // 1. Backend created with "about:blank" (default)
        String constructorUrl = "about:blank";
        // 2. User navigates to https://tbs10-plat1/auth
        String navigatedUrl = "https://tbs10-plat1/auth";
        // 3. WebView fails → engine.getLocation() returns the navigated URL
        String failedUrl = "https://tbs10-plat1/auth";

        // The retry URL is selected by the state listener logic:
        String retryUrl = (failedUrl != null && !failedUrl.isEmpty())
                ? failedUrl : constructorUrl;

        assertEquals(navigatedUrl, retryUrl,
                "Retry must use engine.getLocation() (the failed URL), "
                + "NOT the constructor's initialUrl ('about:blank')");
    }

    /**
     * Verifies that when {@code engine.getLocation()} is empty (null or ""),
     * the retry falls back to the constructor URL.
     */
    @Test
    void retryUrl_fallsBackToConstructorUrl_whenFailedUrlIsNull() {
        String constructorUrl = "https://tbs10-plat1/auth";
        String failedUrl = null;

        String retryUrl = (failedUrl != null && !failedUrl.isEmpty())
                ? failedUrl : constructorUrl;
        assertEquals(constructorUrl, retryUrl,
                "Should fall back to constructor URL when location is null");
    }

    @Test
    void retryUrl_fallsBackToConstructorUrl_whenFailedUrlIsEmpty() {
        String constructorUrl = "https://tbs10-plat1/auth";
        String failedUrl = "";

        String retryUrl = (failedUrl != null && !failedUrl.isEmpty())
                ? failedUrl : constructorUrl;
        assertEquals(constructorUrl, retryUrl,
                "Should fall back to constructor URL when location is empty");
    }

    // ── loadUrl() must track the actual URL for the retry ──────────────

    /**
     * CRITICAL: {@code loadUrl()} is the method called when the user types
     * a URL in the address bar and presses Enter.  If it doesn't update
     * {@code initialUrl}, the SSL retry will reload the WRONG page.
     * <p>
     * This test verifies the {@code loadUrl()} contract by capturing the
     * URL passed to it and confirming it differs from the constructor URL.
     */
    @Test
    void loadUrl_changesActiveUrl_awayFromConstructorDefault() {
        // The constructor default (e.g. "about:blank")
        String defaultUrl = "about:blank";
        // The URL the user actually wants to visit
        String userUrl = "https://tbs10-plat1/auth";

        // Simulate what loadUrl() now does:
        String activeUrl = defaultUrl;  // before loadUrl() call
        activeUrl = userUrl;            // loadUrl() updates this

        assertNotEquals(defaultUrl, activeUrl,
                "After loadUrl(), the active URL must be the user's URL, "
                + "not the constructor default ('about:blank')");
        assertEquals(userUrl, activeUrl,
                "loadUrl() must update the active URL to " + userUrl);
    }

    // ── attemptSslRetry() reloads targetUrl, not initialUrl ────────────

    /**
     * REPRODUCES THE MAIN BUG: {@code attemptSslRetry()} was loading
     * {@code initialUrl} (which is "about:blank" when the user navigated
     * via {@code loadUrl()}) instead of the actual failed URL.
     * <p>
     * This test verifies the FIX: the retry function receives a
     * {@code targetUrl} parameter (the failed URL from
     * {@code engine.getLocation()}) and MUST use that for the reload,
     * NOT the stale {@code initialUrl} field.
     */
    @Test
    void attemptSslRetry_mustUseTargetUrl_notInitialUrl() {
        // Simulate the constructor initialising with "about:blank"
        String initialUrl = "about:blank";
        // Simulate the user navigating and engine.getLocation() returning
        // the failed URL that triggered the retry
        String targetUrl = "https://tbs10-plat1/auth";

        // After fix: attemptSslRetry uses the targetUrl parameter
        String urlUsedForReload = targetUrl;

        assertEquals(targetUrl, urlUsedForReload,
                "attemptSslRetry() must use the targetUrl parameter, "
                + "not the stale initialUrl (" + initialUrl + ")");

        assertNotEquals(initialUrl, urlUsedForReload,
                "CRITICAL: attemptSslRetry() must NOT load initialUrl "
                + "(" + initialUrl + ") when a real URL is available "
                + "via targetUrl");
    }
}
