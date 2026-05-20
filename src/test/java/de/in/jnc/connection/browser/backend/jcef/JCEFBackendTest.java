package de.in.jnc.connection.browser.backend.jcef;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link JCEFBackend}.
 * <p>
 * These tests cover the logic that does <b>not</b> require a live JCEF
 * native browser instance, specifically:
 * <ul>
 *   <li>{@link JCEFBackend#extractHostname(String)} – a pure URL parser</li>
 * </ul>
 * </p>
 * <p>
 * Tests that require a running {@code CefApp} and native libraries are
 * in {@link JCEFDebugTest}; they are disabled by default and must be
 * launched manually with {@code -Djcef.native=true}.
 * </p>
 */
class JCEFBackendTest {

    // ── extractHostname ────────────────────────────────────────────────

    @Test
    void extractHostname_fromSimpleUrl() {
        assertEquals("example.com", JCEFBackend.extractHostname("https://example.com/path"));
    }

    @Test
    void extractHostname_withPort() {
        assertEquals("example.com", JCEFBackend.extractHostname("https://example.com:8443/path"));
    }

    @Test
    void extractHostname_withoutPath() {
        assertEquals("example.com", JCEFBackend.extractHostname("https://example.com"));
    }

    @Test
    void extractHostname_httpScheme() {
        assertEquals("example.org", JCEFBackend.extractHostname("http://example.org/index.html"));
    }

    @Test
    void extractHostname_withSubdomain() {
        assertEquals("sub.domain.co.uk",
                JCEFBackend.extractHostname("https://sub.domain.co.uk/page?q=1"));
    }

    @Test
    void extractHostname_localhost() {
        assertEquals("localhost", JCEFBackend.extractHostname("http://localhost:8080/test"));
    }

    @Test
    void extractHostname_ipAddress() {
        assertEquals("192.168.1.1", JCEFBackend.extractHostname("https://192.168.1.1/admin"));
    }

    @Test
    void extractHostname_nullReturnsUnknown() {
        assertEquals("unknown", JCEFBackend.extractHostname(null));
    }

    @Test
    void extractHostname_emptyReturnsUnknown() {
        assertEquals("unknown", JCEFBackend.extractHostname(""));
    }

    @Test
    void extractHostname_noScheme() {
        assertEquals("host.name", JCEFBackend.extractHostname("host.name/foo"));
    }

    @Test
    void extractHostname_ftpScheme() {
        assertEquals("files.example.com",
                JCEFBackend.extractHostname("ftp://files.example.com/pub"));
    }

}
