package de.in.jnc.connection.browser.backend;

import java.util.List;
import java.util.function.Consumer;

import javax.swing.Action;
import javax.swing.JComponent;

/**
 * Abstraction of a browser engine that can be embedded in a Swing
 * {@link javax.swing.JPanel}.
 * <p>
 * The two concrete implementations are:
 * <ul>
 *   <li>{@link de.in.jnc.connection.browser.backend.javafx.JavaFXWebViewBackend}
 *       — uses JavaFX WebView (the current default)</li>
 *   <li>{@code JCEFBackend} — uses JCEF (Java Chromium Embedded Framework)</li>
 * </ul>
 * A {@link de.in.jnc.connection.browser.BrowserPanel} owns one backend instance
 * and can switch to another at runtime via
 * {@code switchBackend(BrowserBackendType)}.
 */
public interface BrowserBackend {

    /** Returns the type of this backend. */
    BrowserBackendType getType();

    // ── Navigation ─────────────────────────────────────────────────────

    /** Loads the given URL. */
    void loadUrl(String url);

    /** Reloads the current page. */
    void reload();

    /** Navigates back in history, if possible. */
    void goBack();

    /** Navigates forward in history, if possible. */
    void goForward();

    /** Returns {@code true} if the history has a previous entry. */
    boolean canGoBack();

    /** Returns {@code true} if the history has a next entry. */
    boolean canGoForward();

    /** Stops loading the current page. */
    void stopLoading();

    // ── Lifecycle ──────────────────────────────────────────────────────

    /** Releases all resources held by this backend. */
    void dispose();

    /** Returns {@code true} if the backend has been fully initialised. */
    boolean isInitialized();

    // ── View ───────────────────────────────────────────────────────────

    /**
     * Returns the Swing component that should be placed in the main content
     * area of the parent panel.
     */
    JComponent getViewComponent();

    // ── Callbacks ──────────────────────────────────────────────────────

    /**
     * Registers a listener that is called when the page location (URL)
     * changes. Used to keep the address bar in sync.
     *
     * @param listener the listener, or {@code null} to clear
     */
    void setLocationListener(Consumer<String> listener);

    /**
     * Registers a listener that is called when the page title changes.
     * Used to update the tab label.
     *
     * @param listener the listener, or {@code null} to clear
     */
    void setTitleListener(Consumer<String> listener);

    /**
     * Registers a handler for SSL certificate errors.
     * <p>
     * The handler should prompt the user and return {@code true} to accept
     * the certificate or {@code false} to reject it.
     *
     * @param handler the handler, or {@code null} to clear
     */
    void setCertificateErrorHandler(CertificateErrorHandler handler);

    // ── Scripting / Popups ─────────────────────────────────────────────

    /**
     * Executes JavaScript in the context of the current page.
     */
    void executeScript(String script);

    /**
     * Registers a callback for the "Credentials..." context menu entry.
     * <p>
     * The callback receives a {@code Consumer<String>} that, when invoked
     * with a credential value, injects that value into the currently
     * focused input field on the web page via JavaScript.
     *
     * @param callback the credentials callback, or {@code null} to clear
     */
    void setCredentialsCallback(Consumer<Consumer<String>> callback);

    /**
     * Registers a handler for popup window requests. The handler receives
     * the target URL and should open it in a new tab.
     *
     * @param handler the handler, or {@code null} to clear
     */
    void setPopupHandler(Consumer<String> handler);

    // ── Focus management ─────────────────────────────────────────────

    /**
     * Releases the browser's keyboard focus so that Swing components
     * (notably the URL address bar) can receive keyboard input.
     * <p>
     * This is essential for JCEF windowed mode, where the native CEF
     * Canvas (heavyweight AWT) permanently captures all keystrokes
     * unless explicitly told to release focus.
     * <p>
     * In JCEF, this calls {@code CefBrowser.setFocus(false)} which
     * internally invokes {@code canvas.setFocusable(false)}, preventing
     * the Canvas from ever getting keyboard focus until focus is
     * explicitly requested again via {@link #requestFocus()}.
     */
    void releaseFocus();

    /**
     * Gives keyboard focus back to the browser after it was released
     * via {@link #releaseFocus()}.
     * <p>
     * In JCEF, this calls {@code CefBrowser.setFocus(true)} which
     * internally invokes {@code canvas.setFocusable(true)} and
     * {@code canvas.requestFocus()}.
     */
    void requestFocus();

    // ── Context menu ───────────────────────────────────────────────────

    /**
     * Returns a list of {@link Action} objects that should be appended to
     * the browser's right-click context menu.
     * <p>
     * These are typically navigation actions (back, forward, reload) and
     * backend-specific actions.
     */
    List<Action> getContextMenuActions();
}
