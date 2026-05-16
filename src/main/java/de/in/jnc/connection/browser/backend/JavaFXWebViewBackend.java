package de.in.jnc.connection.browser.backend;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.net.ssl.HttpsURLConnection;
import javax.swing.JComponent;
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
 * {@link BrowserBackend} implementation that wraps a JavaFX {@link WebView}
 * embedded via {@link JFXPanel}.
 * <p>
 * This backend supports two SSL strategies:
 * <ul>
 *   <li><b>HTTP/1.1:</b> The JDK SSL layer is used (when
 *       {@code com.sun.webkit.useJVMSSLSocket=true}), so the custom
 *       {@link de.in.jnc.connection.browser.CertificateTrustManager} handles
 *       untrusted certificates.</li>
 *   <li><b>HTTP/2:</b> The native Schannel SSL stack is used, which is
 *       completely independent of the JDK SSL layer. In this case,
 *       certificate acceptance is achieved by importing the certificate
 *       into the Windows certificate store (see
 *       {@link de.in.jnc.connection.browser.CertificateStoreManager}).</li>
 * </ul>
 */
public class JavaFXWebViewBackend implements BrowserBackend {

    private static final Logger LOGGER = LogManager.getLogger(JavaFXWebViewBackend.class);

    // ── JS constants ────────────────────────────────────────────────────

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

    // ── Fields ─────────────────────────────────────────────────────────

    private final JFXPanel jfxPanel;
    private final String initialUrl;

    private volatile WebEngine webEngine;
    private volatile boolean initialized;

    private Consumer<String> locationListener;
    private Consumer<String> titleListener;
    private CertificateErrorHandler certificateErrorHandler;
    private Consumer<String> popupHandler;

    /**
     * Callback invoked when the user selects "Credentials..." from the
     * context menu. Receives a value consumer that injects the selected
     * credential into the focused input field.
     */
    private Consumer<Consumer<String>> credentialsCallback;

    /**
     * Creates a new JavaFX WebView backend.
     *
     * @param url the initial URL to load
     */
    public JavaFXWebViewBackend(String url) {
        this.initialUrl = url;

        // JFXPanel must be created on the EDT.
        // Its constructor calls Platform.startup() internally if needed.
        jfxPanel = new JFXPanel();

        // Initialise WebView on the JavaFX Application Thread.
        Platform.runLater(() -> initialize(url));
    }

    // ── Initialisation (JavaFX Application Thread) ─────────────────────

    private void initialize(String url) {
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

        // ── Title change → notify listener ──────────────────────────
        engine.titleProperty().addListener((obs, oldTitle, newTitle) -> {
            if (titleListener != null && newTitle != null && !newTitle.isEmpty()) {
                titleListener.accept(newTitle);
            }
        });

        // ── Location change → notify listener ───────────────────────
        engine.locationProperty().addListener((obs, oldLoc, newLoc) -> {
            if (locationListener != null && newLoc != null) {
                locationListener.accept(newLoc);
            }
        });

        // ── Popup handling → route to new tab ───────────────────────
        engine.setCreatePopupHandler(config -> {
            WebView popupView = new WebView();
            popupView.getEngine().locationProperty().addListener(
                    (obs, oldUrl, newUrl) -> {
                        if (newUrl != null && !newUrl.isEmpty()
                                && popupHandler != null) {
                            popupHandler.accept(newUrl);
                        }
                    });
            return popupView.getEngine();
        });

        // ── Error handling (incl. SSL failures) ─────────────────────
        AtomicBoolean sslRetryAttempted = new AtomicBoolean(false);

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

                            String retryUrl = (failedUrl != null && !failedUrl.isEmpty())
                                    ? failedUrl : url;
                            new Thread(() -> attemptSslRetry(retryUrl, engine),
                                    "ssl-retry-" + System.currentTimeMillis()).start();
                        }
                    }
                });

        engine.getLoadWorker().exceptionProperty().addListener(
                (obs, oldErr, newErr) -> {
                    if (newErr != null) {
                        LOGGER.warn("WebEngine load error for URL '{}': {}",
                                engine.getLocation(), newErr.getMessage());
                    }
                });

        // ── Load initial URL ────────────────────────────────────────
        if (url != null && !url.isEmpty()) {
            de.in.jnc.connection.browser.CertificateTrustManager.setTargetUrl(url);
            engine.load(url);
        }

        // Attach the Scene to the JFXPanel
        jfxPanel.setScene(new Scene(webView));

        this.initialized = true;
    }

    // ── Context menu ──────────────────────────────────────────────────

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
     * Sets the callback for the "Credentials..." context menu entry.
     * <p>
     * The callback receives a {@code Consumer<String>} that, when invoked
     * with a credential value, injects that value into the currently focused
     * input field on the web page via JavaScript.
     */
    public void setCredentialsCallback(Consumer<Consumer<String>> callback) {
        this.credentialsCallback = callback;
    }

    private void onCredentialsRequested() {
        if (credentialsCallback == null) {
            LOGGER.warn("Credentials requested but no callback is registered");
            return;
        }
        credentialsCallback.accept(this::insertValueIntoActiveElement);
    }

    /**
     * Injects the given value into the previously focused input element
     * on the web page via JavaScript execution.
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

    // ── BrowserBackend implementation ──────────────────────────────────

    @Override
    public BrowserBackendType getType() {
        return BrowserBackendType.JAVAFX_WEBVIEW;
    }

    @Override
    public void loadUrl(String url) {
        WebEngine eng = this.webEngine;
        if (eng != null) {
            de.in.jnc.connection.browser.CertificateTrustManager.setTargetUrl(url);
            Platform.runLater(() -> eng.load(url));
        }
    }

    @Override
    public void reload() {
        WebEngine eng = this.webEngine;
        if (eng != null) {
            Platform.runLater(eng::reload);
        }
    }

    @Override
    public void goBack() {
        WebEngine eng = this.webEngine;
        if (eng != null) {
            Platform.runLater(() -> {
                if (eng.getHistory().getCurrentIndex() > 0) {
                    eng.getHistory().go(-1);
                }
            });
        }
    }

    @Override
    public void goForward() {
        WebEngine eng = this.webEngine;
        if (eng != null) {
            Platform.runLater(() -> {
                if (eng.getHistory().getCurrentIndex()
                        < eng.getHistory().getEntries().size() - 1) {
                    eng.getHistory().go(1);
                }
            });
        }
    }

    @Override
    public boolean canGoBack() {
        WebEngine eng = this.webEngine;
        return eng != null && eng.getHistory().getCurrentIndex() > 0;
    }

    @Override
    public boolean canGoForward() {
        WebEngine eng = this.webEngine;
        return eng != null && eng.getHistory().getCurrentIndex()
                < eng.getHistory().getEntries().size() - 1;
    }

    @Override
    public void stopLoading() {
        WebEngine eng = this.webEngine;
        if (eng != null) {
            Platform.runLater(() -> eng.getLoadWorker().cancel());
        }
    }

    @Override
    public void dispose() {
        LOGGER.debug("Disposing JavaFXWebViewBackend");
        this.initialized = false;
        this.webEngine = null;
        // JFXPanel and WebView are garbage-collected when no longer referenced.
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public JComponent getViewComponent() {
        return jfxPanel;
    }

    @Override
    public void setLocationListener(Consumer<String> listener) {
        this.locationListener = listener;
    }

    @Override
    public void setTitleListener(Consumer<String> listener) {
        this.titleListener = listener;
    }

    @Override
    public void setCertificateErrorHandler(CertificateErrorHandler handler) {
        this.certificateErrorHandler = handler;
    }

    @Override
    public void executeScript(String script) {
        WebEngine eng = this.webEngine;
        if (eng != null) {
            Platform.runLater(() -> {
                try {
                    eng.executeScript(script);
                } catch (Exception e) {
                    LOGGER.warn("Failed to execute script: {}", e.getMessage());
                }
            });
        }
    }

    @Override
    public void setPopupHandler(Consumer<String> handler) {
        this.popupHandler = handler;
    }

    @Override
    public java.util.List<javax.swing.Action> getContextMenuActions() {
        // The context menu is currently implemented as a JavaFX ContextMenu
        // inside this backend. This method returns an empty list for now;
        // it is intended for backends (like JCEF) that use Swing actions.
        return java.util.Collections.emptyList();
    }

    // ── SSL error retry ────────────────────────────────────────────────

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

    private void attemptSslRetry(String targetUrl, WebEngine engine) {
        if (targetUrl == null || targetUrl.isEmpty()
                || !targetUrl.startsWith("https://")) {
            return;
        }

        try {
            LOGGER.info("SSL retry: probing {} via HttpsURLConnection", targetUrl);

            de.in.jnc.connection.browser.CertificateTrustManager.setTargetUrl(targetUrl);

            java.net.URL urlObj = new java.net.URL(targetUrl);
            HttpsURLConnection conn = (HttpsURLConnection) urlObj.openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setInstanceFollowRedirects(true);

            int responseCode = conn.getResponseCode();
            LOGGER.info("SSL retry: HttpsURLConnection returned {} for {}",
                    responseCode, targetUrl);

            final String finalUrl = conn.getURL().toExternalForm();
            Platform.runLater(() -> {
                LOGGER.info("SSL retry: reloading WebView with {}", finalUrl);
                engine.load(finalUrl);
            });

            conn.disconnect();
        } catch (javax.net.ssl.SSLHandshakeException e) {
            LOGGER.warn("SSL retry: user rejected certificate for {}", targetUrl);
        } catch (java.net.SocketTimeoutException e) {
            LOGGER.warn("SSL retry: timeout connecting to {}", targetUrl);
        } catch (Exception e) {
            LOGGER.warn("SSL retry: unexpected error for {}: {}",
                    targetUrl, e.getMessage());
        }
    }
}
