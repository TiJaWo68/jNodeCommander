package de.in.jnc.connection.browser;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.util.function.Consumer;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.in.jnc.connection.browser.backend.BrowserBackend;
import de.in.jnc.connection.browser.backend.BrowserBackendType;
import de.in.jnc.connection.browser.backend.JavaFXWebViewBackend;

/**
 * A Swing {@link JPanel} that embeds a browser engine via a
 * {@link BrowserBackend} delegate with a compact URL navigation toolbar.
 * <p>
 * Currently the default backend is JavaFX WebView. In the future, JCEF will
 * be available as an alternative, switchable at runtime via
 * {@link #switchBackend(BrowserBackendType)}.
 * <p>
 * <b>Multi-tab isolation:</b> Each panel creates its own backend instance,
 * so listeners, cookies, and session state are fully isolated between tabs.
 * <p>
 * <b>Context menu:</b> The browser's right-click context menu contains
 * navigation actions and a "Credentials..." entry for injecting credential
 * values directly into focused input fields.
 */
public class BrowserPanel extends JPanel {

    private static final Logger LOGGER = LogManager.getLogger(BrowserPanel.class);

    private static final int PREFERRED_HEIGHT = 36;
    private static final float NAV_BUTTON_FONT_SIZE = 18f;

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

    // ── Backend ─────────────────────────────────────────────────────────

    private BrowserBackend backend;
    private BrowserBackendType backendType;

    // ── Toolbar components ──────────────────────────────────────────────

    private final JTextField urlField;
    private final JButton backBtn;
    private final JButton forwardBtn;

    // ── Callbacks ───────────────────────────────────────────────────────

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
     * Creates a new browser panel with the default backend (JavaFX WebView)
     * and loads the given URL.
     *
     * @param url the initial URL to load (may be {@code "about:blank"})
     */
    public BrowserPanel(String url) {
        this(url, BrowserBackendType.JAVAFX_WEBVIEW);
    }

    /**
     * Creates a new browser panel with the specified backend type.
     *
     * @param url  the initial URL to load
     * @param type the backend type to use
     */
    public BrowserPanel(String url, BrowserBackendType type) {
        super(new BorderLayout());

        this.backendType = type;

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

        // ── Create backend ─────────────────────────────────────────────
        this.backend = createBackend(type, url);
        add(backend.getViewComponent(), BorderLayout.CENTER);

        // ── Wire Swing navigation buttons ──────────────────────────────
        backBtn.addActionListener(e -> backend.goBack());
        forwardBtn.addActionListener(e -> backend.goForward());
        refreshBtn.addActionListener(e -> backend.reload());

        urlField.addActionListener(e -> {
            String input = urlField.getText().trim();
            if (input.isEmpty()) {
                return;
            }
            if (!input.startsWith("http://") && !input.startsWith("https://")
                    && !input.startsWith("about:")) {
                input = "https://" + input;
            }
            backend.loadUrl(input);
        });

        // ── Wire callbacks ─────────────────────────────────────────────
        backend.setLocationListener(this::onLocationChanged);
        backend.setTitleListener(this::onTitleChanged);

        // If the backend is JavaFXWebViewBackend, wire the credentials callback
        if (backend instanceof JavaFXWebViewBackend) {
            ((JavaFXWebViewBackend) backend).setCredentialsCallback(
                    valueInserter -> {
                        if (credentialsCallback != null) {
                            credentialsCallback.accept(valueInserter);
                        }
                    });
        }
    }

    /**
     * Creates a backend instance for the given type.
     */
    private static BrowserBackend createBackend(BrowserBackendType type, String url) {
        return switch (type) {
            case JAVAFX_WEBVIEW -> new JavaFXWebViewBackend(url);
            case JCEF -> throw new UnsupportedOperationException(
                    "JCEF backend not yet implemented");
        };
    }

    /**
     * Switches the browser backend at runtime.
     * <p>
     * The current backend is disposed and replaced with a new one of the
     * given type, which then loads the currently displayed URL.
     *
     * @param newType the backend type to switch to
     */
    public void switchBackend(BrowserBackendType newType) {
        if (newType == this.backendType) {
            return;
        }

        String currentUrl = urlField.getText();

        // Dispose old backend
        backend.setLocationListener(null);
        backend.setTitleListener(null);
        remove(backend.getViewComponent());
        backend.dispose();

        // Create and add new backend
        this.backendType = newType;
        this.backend = createBackend(newType, currentUrl);
        add(backend.getViewComponent(), BorderLayout.CENTER);

        // Wire callbacks
        backend.setLocationListener(this::onLocationChanged);
        backend.setTitleListener(this::onTitleChanged);

        revalidate();
        repaint();
    }

    // ── Internal callback handlers ──────────────────────────────────────

    private void onLocationChanged(String newUrl) {
        SwingUtilities.invokeLater(() -> urlField.setText(newUrl));
    }

    private void onTitleChanged(String newTitle) {
        if (titleCallback != null && newTitle != null && !newTitle.isEmpty()) {
            SwingUtilities.invokeLater(
                    () -> titleCallback.onTitleChanged(newTitle));
        }
    }

    // ── Public callback setters ─────────────────────────────────────────

    /**
     * Registers a callback for popup-window creation requests.
     */
    public void setNewTabCallback(NewTabCallback callback) {
        this.newTabCallback = callback;
        backend.setPopupHandler(url -> {
            if (callback != null) {
                SwingUtilities.invokeLater(() -> callback.openNewTab(url));
            }
        });
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
     */
    public void setCredentialsCallback(Consumer<Consumer<String>> callback) {
        this.credentialsCallback = callback;
    }

    /**
     * Returns the current backend type.
     */
    public BrowserBackendType getBackendType() {
        return backendType;
    }

    /**
     * Releases resources held by this panel.
     */
    public void dispose() {
        LOGGER.debug("Disposing BrowserPanel");
        if (backend != null) {
            backend.dispose();
        }
    }
}
