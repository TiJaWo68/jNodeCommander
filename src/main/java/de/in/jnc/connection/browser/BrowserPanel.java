package de.in.jnc.connection.browser;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.in.jnc.connection.browser.backend.BrowserBackend;
import de.in.jnc.connection.browser.backend.jcef.JCEFBackend;

/**
 * A Swing {@link JPanel} that embeds a JCEF (Chromium) browser engine
 * with a compact URL navigation toolbar.
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

    /** Callback invoked on Ctrl+D to add current URL as bookmark. */
    private Runnable bookmarkCallback;

    /** Callback invoked when a new URL is loaded (for history tracking). */
    private Consumer<String> historyCallback;

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
     * Creates a new browser panel with the JCEF (Chromium) backend
     * and loads the given URL.
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

        // ── Create backend ─────────────────────────────────────────────
        this.backend = new JCEFBackend(url);
        add(backend.getViewComponent(), BorderLayout.CENTER);

        // ── Focus: prevent CEF from grabbing focus on creation ────────
        // In JCEF windowed mode, CefBrowserWr asynchronously calls
        // setFocus(true) via a 100ms Timer during browser parenting.
        // A double invokeLater defers our releaseFocus() until after
        // CEF's Timer action has run, so our call is the last one.
        SwingUtilities.invokeLater(() -> {
            SwingUtilities.invokeLater(backend::releaseFocus);
        });

        // ── Wire Swing navigation buttons ──────────────────────────────
        backBtn.addActionListener(e -> backend.goBack());
        forwardBtn.addActionListener(e -> backend.goForward());
        refreshBtn.addActionListener(e -> backend.reload());

        // ── Ctrl+D → bookmark current URL ─────────────────────────────
        urlField.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(java.awt.event.KeyEvent e) {
                if (e.isControlDown() && e.getKeyCode() == java.awt.event.KeyEvent.VK_D) {
                    if (bookmarkCallback != null) {
                        bookmarkCallback.run();
                    }
                }
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
            backend.loadUrl(input);
        });

        // ── Focus coordination for JCEF windowed mode ──────────────────
        // Official JCEF pattern (from CefBrowserWr / CefBrowser_N):
        //   setFocus(true)  → canvas.setFocusable(true) + requestFocus()
        //   setFocus(false) → canvas.setFocusable(false)
        //
        // When the Canvas is NOT focusable, keyboard events flow to Swing
        // components. When it IS focusable, events go to the CEF browser.
        //
        // We use mouse listeners to toggle focusability:
        //   - Click on toolbar (URL bar, buttons) → release CEF focus
        //   - Click on browser view area           → request CEF focus
        MouseAdapter swingFocusHandler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                backend.releaseFocus();
            }
        };
        urlField.addMouseListener(swingFocusHandler);
        backBtn.addMouseListener(swingFocusHandler);
        forwardBtn.addMouseListener(swingFocusHandler);
        refreshBtn.addMouseListener(swingFocusHandler);

        backend.getViewComponent().addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                backend.requestFocus();
            }
        });

        // ── Wire callbacks ─────────────────────────────────────────────
        backend.setLocationListener(this::onLocationChanged);
        backend.setTitleListener(this::onTitleChanged);

        // Wire the credentials callback (supported by all backends)
        backend.setCredentialsCallback(
                valueInserter -> {
                    if (credentialsCallback != null) {
                        credentialsCallback.accept(valueInserter);
                    }
                });
    }

    // ── Internal callback handlers ──────────────────────────────────────

    private void onLocationChanged(String newUrl) {
        SwingUtilities.invokeLater(() -> urlField.setText(newUrl));
        if (historyCallback != null && newUrl != null
                && !newUrl.isEmpty() && !"about:blank".equals(newUrl)) {
            historyCallback.accept(newUrl);
        }
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
     * Registers a callback for Ctrl+D bookmark shortcut.
     */
    public void setBookmarkCallback(Runnable callback) {
        this.bookmarkCallback = callback;
    }

    /**
     * Registers a callback for URL history tracking.
     */
    public void setHistoryCallback(Consumer<String> callback) {
        this.historyCallback = callback;
    }

    /**
     * Requests keyboard focus on the URL address bar.
     * <p>
     * In JCEF windowed mode, the native Canvas must first release its
     * keyboard hook via {@code backend.releaseFocus()} before any Swing
     * text component can receive keystrokes.
     */
    /**
     * Returns the URL currently displayed in the address bar.
     */
    public String getCurrentUrl() {
        return urlField.getText();
    }

    public void requestUrlBarFocus() {
        backend.releaseFocus();
        urlField.requestFocusInWindow();
        urlField.selectAll();
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
