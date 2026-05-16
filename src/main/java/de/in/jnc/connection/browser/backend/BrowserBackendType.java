package de.in.jnc.connection.browser.backend;

/**
 * Enumeration of supported browser backends.
 * <p>
 * Each value identifies a concrete implementation of {@link BrowserBackend}.
 */
public enum BrowserBackendType {

    /** JavaFX WebView — default backend, uses JFXPanel + WebEngine. */
    JAVAFX_WEBVIEW,

    /** JCEF (Java Chromium Embedded Framework) — optional backend. */
    JCEF
}
