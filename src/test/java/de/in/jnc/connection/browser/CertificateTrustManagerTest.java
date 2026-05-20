package de.in.jnc.connection.browser;

import static org.junit.jupiter.api.Assertions.*;

import java.security.cert.X509Certificate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CertificateTrustManager}.
 * <p>
 * Covers hostname extraction, certificate fingerprinting, the session
 * acceptance cache, and the cross-thread visibility of
 * {@code pendingTargetUrl} (critical because the URL is set on the
 * JavaFX Application Thread but consumed on HTTP worker threads).
 */
class CertificateTrustManagerTest {

    @BeforeEach
    void resetState() {
        // Clear the session cache between tests to avoid cross-test
        // contamination from rememberAccepted()
        CertificateTrustManager.resetAcceptedCertificatesForTest();
        // Also reset the pending URL
        CertificateTrustManager.setTargetUrl(null);
    }

    // ── extractHostname() ─────────────────────────────────────────────

    @Test
    void extractHostname_normalUrl() {
        assertEquals("tbs10-plat1",
                CertificateTrustManager.extractHostname("https://tbs10-plat1/auth"));
    }

    @Test
    void extractHostname_withPort() {
        assertEquals("server.example.com",
                CertificateTrustManager.extractHostname("https://server.example.com:8443/path"));
    }

    @Test
    void extractHostname_ipAddress() {
        assertEquals("192.168.178.231",
                CertificateTrustManager.extractHostname("https://192.168.178.231/auth"));
    }

    @Test
    void extractHostname_withUserInfo() {
        assertEquals("host.com",
                CertificateTrustManager.extractHostname("https://user@host.com/path"));
    }

    @Test
    void extractHostname_nullReturnsUnknown() {
        assertEquals("unknown",
                CertificateTrustManager.extractHostname(null));
    }

    @Test
    void extractHostname_emptyReturnsUnknown() {
        assertEquals("unknown",
                CertificateTrustManager.extractHostname(""));
    }

    @Test
    void extractHostname_httpUrl() {
        assertEquals("example.com",
                CertificateTrustManager.extractHostname("http://example.com/"));
    }

    @Test
    void extractHostname_noPath() {
        assertEquals("keycloak.internal",
                CertificateTrustManager.extractHostname("https://keycloak.internal"));
    }

    // ── fingerprint() ─────────────────────────────────────────────────

    @Test
    void fingerprint_isHexString() throws Exception {
        // Generate a real self-signed cert for testing
        X509Certificate cert = TestCertificates.createSelfSignedCert();
        String fp = CertificateTrustManager.fingerprint(cert);
        assertNotNull(fp);
        // SHA-256 hex = 64 hex chars
        assertTrue(fp.matches("[0-9a-f]{64}"),
                "Fingerprint should be a 64-char hex string, got: " + fp);
    }

    @Test
    void fingerprint_isDeterministic() throws Exception {
        X509Certificate cert = TestCertificates.createSelfSignedCert();
        String fp1 = CertificateTrustManager.fingerprint(cert);
        String fp2 = CertificateTrustManager.fingerprint(cert);
        assertEquals(fp1, fp2,
                "Fingerprint must be deterministic for the same certificate");
    }

    // ── Session cache (isAlreadyAccepted / rememberAccepted) ──────────

    @Test
    void isAlreadyAccepted_unknownCert_returnsFalse() throws Exception {
        X509Certificate cert = TestCertificates.createSelfSignedCert();
        assertFalse(CertificateTrustManager.isAlreadyAccepted(new X509Certificate[]{cert}),
                "A new certificate should not be in the accepted cache");
    }

    @Test
    void rememberAccepted_then_isAlreadyAccepted_returnsTrue() throws Exception {
        X509Certificate cert = TestCertificates.createSelfSignedCert();
        X509Certificate[] chain = new X509Certificate[]{cert};

        // Initially not accepted
        assertFalse(CertificateTrustManager.isAlreadyAccepted(chain));

        // Accept it
        CertificateTrustManager.rememberAccepted(chain);
        assertTrue(CertificateTrustManager.isAlreadyAccepted(chain),
                "After rememberAccepted, isAlreadyAccepted should return true");
    }

    @Test
    void isAlreadyAccepted_nullChain_returnsFalse() {
        assertFalse(CertificateTrustManager.isAlreadyAccepted(null));
    }

    @Test
    void isAlreadyAccepted_emptyChain_returnsFalse() {
        assertFalse(CertificateTrustManager.isAlreadyAccepted(new X509Certificate[0]));
    }

    // ── Cross-thread pendingTargetUrl (AtomicReference fix) ───────────

    /**
     * CRITICAL: This test verifies the fix for the {@code ThreadLocal → AtomicReference}
     * migration.  The URL is set on one thread (simulating the JavaFX Application
     * Thread) and read on another thread (simulating the
     * {@code HttpClient-1-Worker-*} thread that runs
     * {@code checkServerTrusted()}).
     * <p>
     * A {@code ThreadLocal} would have made the URL invisible across threads —
     * exactly the bug that caused {@code extractHostname(null)} to return
     * {@code "unknown"} and the PEM file to be named {@code accepted-unknown.pem}
     * with stale content.
     */
    @Test
    void pendingTargetUrl_isVisibleAcrossThreads() throws Exception {
        String expectedUrl = "https://tbs10-plat1/auth";
        AtomicReference<String> resultFromOtherThread = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        // Set on the current thread (simulates JavaFX Application Thread)
        CertificateTrustManager.setTargetUrl(expectedUrl);

        // Read on a different thread (simulates HttpClient-1-Worker)
        Thread worker = new Thread(() -> {
            resultFromOtherThread.set(CertificateTrustManager.getPendingTargetUrlForTest());
            latch.countDown();
        }, "test-worker");
        worker.start();
        latch.await();

        assertEquals(expectedUrl, resultFromOtherThread.get(),
                "AtomicReference must make the URL visible across threads");
    }

    @Test
    void pendingTargetUrl_canBeUpdated() {
        CertificateTrustManager.setTargetUrl("https://first.url");
        assertEquals("https://first.url",
                CertificateTrustManager.getPendingTargetUrlForTest());

        CertificateTrustManager.setTargetUrl("https://second.url");
        assertEquals("https://second.url",
                CertificateTrustManager.getPendingTargetUrlForTest(),
                "AtomicReference should allow updates");
    }

    @Test
    void pendingTargetUrl_defaultIsNull() {
        // Reset first
        CertificateTrustManager.setTargetUrl(null);
        assertNull(CertificateTrustManager.getPendingTargetUrlForTest());
    }
}
