package de.in.jnc.connection.browser;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.net.ssl.HttpsURLConnection;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.MouseButton;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;

/**
 * A Swing {@link JPanel} that embeds a JavaFX {@link WebView} browser with a
 * compact URL navigation toolbar.
 * <p>
 * Provides back / forward / refresh navigation and an address bar that stays in
 * sync with the browser's current URL. Popup windows are suppressed and
 * forwarded to the configured {@link NewTabCallback} instead.
 * <p>
 * Uses the standard Swing+JavaFX integration pattern:
 * <ol>
 *   <li>The {@link JFXPanel} is created on the AWT Event Dispatch Thread.
 *       Its constructor automatically calls {@link Platform#startup(Runnable)}
 *       if the JavaFX runtime is not yet initialised.</li>
 *   <li>All WebView / WebEngine operations are performed on the JavaFX
 *       Application Thread via {@link Platform#runLater(Runnable)}.</li>
 * </ol>
 * <p>
 * <b>Video playback:</b> Unlike JCEF (which ships with open-source codecs
 * only), the JavaFX WebView uses the operating system's media framework
 * (Windows Media Foundation on Windows), so H.264 / AAC videos play without
 * any additional setup or licensing issues.
 * <p>
 * <b>Multi-tab isolation:</b> Each panel creates its own {@link WebView}
 * instance, so listeners, cookies, and session state are fully isolated
 * between tabs.
 * <p>
 * <b>Context menu:</b> The default WebView context menu is replaced by a
 * custom menu containing navigation actions and a "Credentials..." entry
 * for injecting credential values directly into focused input fields.
 */
public class BrowserPanel extends JPanel {

    private static final Logger LOGGER = LogManager.getLogger(BrowserPanel.class);

    private static final int PREFERRED_HEIGHT = 36;
    private static final float NAV_BUTTON_FONT_SIZE = 18f;

    private static final String JS_SAVE_ACTIVE_ELEMENT =
            "window.__jnc_activeElement = document.activeElement;";

    private static final String JS_INSERT_VALUE_TEMPLATE =
            "(function(val) {"
            + "  var el = window.__jnc_activeElement;"
            + "  if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) {"
            + "    el.focus();"
            + "    el.value = val;"
            + "    el.dispatchEvent(new Event('input', { bubbles: true }));"
            + "    el.dispatchEvent(new Event('change', { bubbles: true }));"
            + "  }"
            + "})('%s');";

    /**
     * Prism rendering properties must be set before any JavaFX class is loaded.
     * This static initialiser runs at class-load time, which is guaranteed to
     * happen before the first JFXPanel constructor (and thus before Platform.
     * startup() is called internally).
     * <p>
     * <b>Key settings:</b>
     * <ul>
     *   <li>{@code prism.order=d3d} – Force Direct3D hardware rendering on
     *       Windows (instead of Prism's auto-detection which may fall back to
     *       software for embedded WebView).</li>
     *   <li>{@code prism.forceGPU=true} – Refuse software fallback even if
     *       hardware detection is uncertain.</li>
     *   <li>{@code javafx.embed.swing.useNativeBuffer=true} – Use a native
     *       Direct3D shared surface for JFXPanel instead of copying pixels
     *       through a BufferedImage. This is the single most important
     *       optimisation for JFXPanel rendering performance.</li>
     *   <li>{@code prism.vsync=false} – Disable vertical sync for lower
     *       input-to-display latency during WebView scrolling.</li>
     *   <li>{@code com.sun.webkit.useJVMSSLSocket=true} – Make JavaFX WebView
     *       use the JDK's own SSL socket factory instead of WebKit's native
     *       SSL implementation. This is essential for trusting internal CA /
     *       self-signed certificates, because the JDK's SSL layer can be
     *       configured via {@link javax.net.ssl.SSLContext#setDefault} or by
     *       importing the CA into the JDK trust store, whereas WebKit's native
     *       SSL stack cannot be configured from Java code.</li>
     * </ul>
     */
    static {
        if (!System.getProperties().containsKey("prism.order")) {
            System.setProperty("prism.order", "d3d");
        }
        if (!System.getProperties().containsKey("prism.forceGPU")) {
            System.setProperty("prism.forceGPU", "true");
        }
        if (!System.getProperties().containsKey("javafx.embed.swing.useNativeBuffer")) {
            System.setProperty("javafx.embed.swing.useNativeBuffer", "true");
        }
        if (!System.getProperties().containsKey("prism.vsync")) {
            System.setProperty("prism.vsync", "false");
        }
        // Force WebView to use JDK SSL (respects JDK trust store and custom SSLContext)
        // Must be set before any JFXPanel/WebView is created.
        if (!System.getProperties().containsKey("com.sun.webkit.useJVMSSLSocket")) {
            System.setProperty("com.sun.webkit.useJVMSSLSocket", "true");
        }
        // Install a CertificateTrustManager that prompts the user when a
        // server certificate is not trusted by the JVM's default trust store.
        // This allows the user to accept internal CA / self-signed certificates
        // (common in K8s environments) on a case-by-case basis.
        CertificateTrustManager.installAsDefault();
    }

    private final JFXPanel jfxPanel;
    private final JTextField urlField;
    private final JButton backBtn;
    private final JButton forwardBtn;

    /**
     * Volatile reference to the WebEngine, set by the JavaFX Application
     * Thread during initialisation. {@code volatile} ensures visibility
     * across threads without locking.
     */
    private volatile WebEngine webEngine;

    private NewTabCallback newTabCallback;
    private TitleChangeCallback titleCallback;

    /**
     * Callback invoked when the user selects "Credentials..." from the
     * browser context menu. The outer consumer receives an inner consumer
     * (the "value inserter") that will inject the selected credential value
     * into the focused input field via JavaScript.
     */
    private Consumer<Consumer<String>> credentialsCallback;

    /**
     * Callback invoked when the browser requests to open a new tab (popup).
     */
    @FunctionalInterface
    public interface NewTabCallback {
        void openNewTab(String url);
    }

    /**
     * Callback invoked when the page title changes (for updating the tab label).
     */
    @FunctionalInterface
    public interface TitleChangeCallback {
        void onTitleChanged(String title);
    }

    /**
     * Creates a new browser panel and loads the given URL.
     *
     * @param url the initial URL to load (may be {@code "about:blank"})
     */
    public BrowserPanel(String url) {
        super(new BorderLayout());

        // ── Navigation toolbar (constructed on EDT) ────────────────────
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.setRollover(true);

        backBtn = new JButton("\u2190");  // ←
        forwardBtn = new JButton("\u2192"); // →
        JButton refreshBtn = new JButton("\u21BB"); // ↻

        Font navFont = backBtn.getFont().deriveFont(NAV_BUTTON_FONT_SIZE);
        backBtn.setFont(navFont);
        forwardBtn.setFont(navFont);
        refreshBtn.setFont(navFont);

        urlField = new JTextField(url);
        urlField.setPreferredSize(new Dimension(200, PREFERRED_HEIGHT));

        toolbar.add(backBtn);
        toolbar.add(forwardBtn);
        toolbar.add(refreshBtn);
        toolbar.add(urlField);

        add(toolbar, BorderLayout.NORTH);

        // ── JavaFX WebView (via JFXPanel) ──────────────────────────────
        // JFXPanel must be created on the EDT. Its constructor will call
        // Platform.startup() internally if the JavaFX runtime is not yet
        // initialised – no explicit initialisation necessary.
        jfxPanel = new JFXPanel();
        add(jfxPanel, BorderLayout.CENTER);

        // ── Wire Swing navigation buttons (EDT) ────────────────────────
        // Each action captures the volatile WebEngine reference and
        // dispatches the actual work to the JavaFX Application Thread.
        backBtn.addActionListener(e -> {
            WebEngine eng = this.webEngine;
            if (eng != null) {
                Platform.runLater(() -> {
                    if (eng.getHistory().getCurrentIndex() > 0) {
                        eng.getHistory().go(-1);
                    }
                });
            }
        });

        forwardBtn.addActionListener(e -> {
            WebEngine eng = this.webEngine;
            if (eng != null) {
                Platform.runLater(() -> {
                    if (eng.getHistory().getCurrentIndex()
                            < eng.getHistory().getEntries().size() - 1) {
                        eng.getHistory().go(1);
                    }
                });
            }
        });

        refreshBtn.addActionListener(e -> {
            WebEngine eng = this.webEngine;
            if (eng != null) {
                Platform.runLater(eng::reload);
            }
        });

        urlField.addActionListener(e -> {
            String input = urlField.getText().trim();
            if (input.isEmpty()) {
                return;
            }
            if (!input.startsWith("http://") && !input.startsWith("https://")
                    && !input.startsWith("about:")) {
                input = "https://" + input;
            }
            final String finalUrl = input;
            WebEngine eng = this.webEngine;
            if (eng != null) {
                CertificateTrustManager.setTargetUrl(finalUrl);
                Platform.runLater(() -> eng.load(finalUrl));
            }
        });

        // ── Initialise WebView on the JavaFX Application Thread ────────
        Platform.runLater(() -> initializeWebView(url));
    }

    /**
     * Initialises the JavaFX WebView, installs listeners for title/location
     * changes, popup handling, and the custom right-click context menu.
     * <p>
     * Must be called on the JavaFX Application Thread.
     */
    private void initializeWebView(String url) {
        WebView webView = new WebView();
        WebEngine engine = webView.getEngine();
        this.webEngine = engine;

        // ── Disable default context menu, install custom one ─────────
        webView.setContextMenuEnabled(false);
        ContextMenu contextMenu = createContextMenu(webView, engine);

        webView.setOnMousePressed(event -> {
            if (event.getButton() == MouseButton.SECONDARY) {
                engine.executeScript(JS_SAVE_ACTIVE_ELEMENT);
                contextMenu.show(webView, event.getScreenX(), event.getScreenY());
            } else {
                contextMenu.hide();
            }
        });

        // ── Title change → update tab label ──────────────────────────
        engine.titleProperty().addListener((obs, oldTitle, newTitle) -> {
            if (titleCallback != null && newTitle != null
                    && !newTitle.isEmpty()) {
                SwingUtilities.invokeLater(
                        () -> titleCallback.onTitleChanged(newTitle));
            }
        });

        // ── Location change → keep URL field in sync ────────────────
        engine.locationProperty().addListener((obs, oldLoc, newLoc) -> {
            if (newLoc != null) {
                SwingUtilities.invokeLater(
                        () -> urlField.setText(newLoc));
            }
        });

        // ── Popup handling → route to new tab ───────────────────────
        // WebView's createPopupHandler returns a WebView for the popup.
        // We create a temporary view and listen for its location change
        // to capture the target URL, then forward it to the callback.
        engine.setCreatePopupHandler(config -> {
            WebView popupView = new WebView();
            popupView.getEngine().locationProperty().addListener(
                    (obs, oldUrl, newUrl) -> {
                        if (newUrl != null && !newUrl.isEmpty()
                                && newTabCallback != null) {
                            SwingUtilities.invokeLater(
                                    () -> newTabCallback.openNewTab(newUrl));
                        }
                    });
            return popupView.getEngine();
        });

        // ── Error handling (incl. SSL failures) ─────────────────────
        // Track whether we already attempted an SSL retry for the
        // current URL to prevent infinite retry loops.
        AtomicBoolean sslRetryAttempted = new AtomicBoolean(false);

        // Listen for load state changes to detect SSL-related failures
        // and automatically retry via HttpsURLConnection (which uses our
        // CertificateTrustManager dialog). If the user accepts the cert,
        // it gets imported into the Windows certificate store, and the
        // WebView reload succeeds.
        engine.getLoadWorker().stateProperty().addListener(
                (obs, oldState, newState) -> {
                    if (newState == Worker.State.FAILED && !sslRetryAttempted.get()) {
                        Throwable error = engine.getLoadWorker().getException();
                        String failedUrl = engine.getLocation();
                        LOGGER.warn("WebEngine load FAILED for '{}': {}",
                                failedUrl, error != null ? error.getMessage() : "unknown");

                        if (error != null && isLikelySslError(error)) {
                            sslRetryAttempted.set(true);
                            LOGGER.info("SSL-related load failure detected, "
                                    + "attempting retry via HttpsURLConnection...");

                            // Retry on a background thread (not FX/Swing thread)
                            String retryUrl = failedUrl != null && !failedUrl.isEmpty()
                                    ? failedUrl : url;
                            new Thread(() -> attemptSslRetry(retryUrl, engine),
                                    "ssl-retry-" + System.currentTimeMillis()).start();
                        }
                    }
                });

        // Also log exception property changes (additionally to state listener)
        engine.getLoadWorker().exceptionProperty().addListener(
                (obs, oldErr, newErr) -> {
                    if (newErr != null) {
                        LOGGER.warn("WebEngine load error for URL '{}': {}",
                                engine.getLocation(), newErr.getMessage());
                    }
                });

        // ── Load initial URL ────────────────────────────────────────
        if (url != null && !url.isEmpty()) {
            CertificateTrustManager.setTargetUrl(url);
            engine.load(url);
        }

        // Attach the Scene to the JFXPanel
        jfxPanel.setScene(new Scene(webView));
    }

    /**
     * Creates the custom right-click context menu with navigation actions
     * and a "Credentials..." entry.
     */
    private ContextMenu createContextMenu(WebView webView, WebEngine engine) {
        MenuItem backItem = new MenuItem("\u2B05  Zurück");
        backItem.setOnAction(e -> {
            if (engine.getHistory().getCurrentIndex() > 0) {
                engine.getHistory().go(-1);
            }
        });

        MenuItem forwardItem = new MenuItem("\u27A1  Vorwärts");
        forwardItem.setOnAction(e -> {
            if (engine.getHistory().getCurrentIndex()
                    < engine.getHistory().getEntries().size() - 1) {
                engine.getHistory().go(1);
            }
        });

        MenuItem reloadItem = new MenuItem("\uD83D\uDD04  Neu laden");
        reloadItem.setOnAction(e -> engine.reload());

        MenuItem credentialsItem = new MenuItem("\uD83D\uDD11  Credentials...");
        credentialsItem.setOnAction(e -> onCredentialsRequested());

        return new ContextMenu(
                backItem,
                forwardItem,
                reloadItem,
                new SeparatorMenuItem(),
                credentialsItem
        );
    }

    /**
     * Handles the "Credentials..." context menu click.
     * <p>
     * Invokes the credentials callback on the EDT, passing the
     * {@link #insertValueIntoActiveElement(String)} method as the
     * value consumer. This allows the credentials dialog to inject
     * the selected value directly into the focused input field.
     */
    private void onCredentialsRequested() {
        if (credentialsCallback == null) {
            LOGGER.warn("Credentials requested but no callback is registered");
            return;
        }
        SwingUtilities.invokeLater(() ->
                credentialsCallback.accept(this::insertValueIntoActiveElement));
    }

    /**
     * Injects the given value into the previously focused input element
     * on the web page via JavaScript execution.
     * <p>
     * The active element was saved when the context menu was shown
     * (via {@link #JS_SAVE_ACTIVE_ELEMENT}). This method restores focus
     * to that element, sets its value, and dispatches {@code input} and
     * {@code change} events so that modern JS frameworks (React, Angular,
     * Vue) detect the programmatic change.
     *
     * @param value the credential value to inject (username or password)
     */
    private void insertValueIntoActiveElement(String value) {
        WebEngine eng = this.webEngine;
        if (eng == null) {
            LOGGER.warn("Cannot inject value: WebEngine not initialised");
            return;
        }
        String escaped = escapeJavaScriptString(value);
        String script = String.format(JS_INSERT_VALUE_TEMPLATE, escaped);
        Platform.runLater(() -> {
            try {
                eng.executeScript(script);
                LOGGER.debug("Injected credential value into active input element");
            } catch (Exception e) {
                LOGGER.warn("Failed to inject value into web page: {}", e.getMessage());
            }
        });
    }

    /**
     * Escapes a string for safe embedding in a JavaScript single-quoted
     * string literal. Handles single quotes, backslashes, and newlines.
     */
    private static String escapeJavaScriptString(String input) {
        if (input == null) {
            return "";
        }
        return input
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    // ── SSL error retry ─────────────────────────────────────────────────

    /**
     * Checks whether a {@link Throwable} is likely an SSL-related error.
     * <p>
     * WebView's HTTP/2 native loader typically reports errors like
     * {@code "SSL handshake failed"} or {@code "certificate unknown"}.
     */
    private static boolean isLikelySslError(Throwable error) {
        if (error == null) {
            return false;
        }
        String msg = error.getMessage();
        if (msg == null) {
            return false;
        }
        String lower = msg.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("ssl")
                || lower.contains("handshake")
                || lower.contains("certificate")
                || lower.contains("trust")
                || lower.contains("untrusted");
    }

    /**
     * Attempts to connect to the given URL via
     * {@link javax.net.ssl.HttpsURLConnection}, which uses the JDK's default
     * SSL context and thus goes through our {@link CertificateTrustManager}.
     * If the user accepts the certificate in the dialog,
     * {@link CertificateStoreManager#importAcceptedCertificate} imports it
     * into the Windows certificate store (Schannel), after which WebView can
     * successfully load the page.
     * <p>
     * Called from a background thread.
     */
    private void attemptSslRetry(String targetUrl, WebEngine engine) {
        if (targetUrl == null || targetUrl.isEmpty()
                || !targetUrl.startsWith("https://")) {
            return;
        }

        try {
            LOGGER.info("SSL retry: probing {} via HttpsURLConnection", targetUrl);

            CertificateTrustManager.setTargetUrl(targetUrl);

            java.net.URL urlObj = new java.net.URL(targetUrl);
            HttpsURLConnection conn = (HttpsURLConnection) urlObj.openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setInstanceFollowRedirects(true);

            // This triggers the SSL handshake, which invokes our custom
            // CertificateTrustManager. If the certificate is untrusted, the
            // user is prompted via CertificateWarningDialog. If accepted, the
            // certificate is automatically imported into the Windows store
            // by CertificateTrustManager.
            int responseCode = conn.getResponseCode();
            LOGGER.info("SSL retry: HttpsURLConnection returned {} for {}",
                    responseCode, targetUrl);

            // If we reach here without an exception (or the user accepted
            // the cert and it was imported), reload the WebView
            final String finalUrl = conn.getURL().toExternalForm();
            Platform.runLater(() -> {
                LOGGER.info("SSL retry: reloading WebView with {}", finalUrl);
                engine.load(finalUrl);
            });

            conn.disconnect();
        } catch (javax.net.ssl.SSLHandshakeException e) {
            // The CertificateTrustManager threw the original CertificateException
            // wrapped in an SSLHandshakeException because the user rejected the cert.
            // Do not retry — the user declined.
            LOGGER.warn("SSL retry: user rejected certificate for {}", targetUrl);
        } catch (java.net.SocketTimeoutException e) {
            LOGGER.warn("SSL retry: timeout connecting to {}", targetUrl);
        } catch (Exception e) {
            LOGGER.warn("SSL retry: unexpected error for {}: {}",
                    targetUrl, e.getMessage());
        }
    }

    // ── Callback setters ────────────────────────────────────────────────

    /**
     * Registers a callback for popup-window creation requests.
     */
    public void setNewTabCallback(NewTabCallback callback) {
        this.newTabCallback = callback;
    }

    /**
     * Registers a callback for page title changes.
     */
    public void setTitleCallback(TitleChangeCallback callback) {
        this.titleCallback = callback;
    }

    /**
     * Registers a callback for the "Credentials..." context menu entry.
     * <p>
     * The callback receives a {@code Consumer<String>} that, when invoked
     * with a credential value, injects that value into the currently focused
     * input field on the web page via JavaScript.
     *
     * @param callback the credentials callback, or {@code null} to disable
     */
    public void setCredentialsCallback(Consumer<Consumer<String>> callback) {
        this.credentialsCallback = callback;
    }

    /**
     * Releases resources held by this panel.
     * <p>
     * For JavaFX WebView, no explicit cleanup is required – the garbage
     * collector handles the WebView and JFXPanel when this panel is removed
     * from the container.
     */
    public void dispose() {
        LOGGER.debug("Disposing BrowserPanel (JavaFX WebView)");
    }
}
