package de.in.jnc.connection.browser;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
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
                Platform.runLater(() -> eng.load(finalUrl));
            }
        });

        // ── Initialise WebView on the JavaFX Application Thread ────────
        Platform.runLater(() -> {
            WebView webView = new WebView();
            WebEngine engine = webView.getEngine();
            this.webEngine = engine; // publish for the EDT action listeners

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

            // ── Load initial URL ────────────────────────────────────────
            if (url != null && !url.isEmpty()) {
                engine.load(url);
            }

            // Attach the Scene to the JFXPanel
            jfxPanel.setScene(new Scene(webView));
        });
    }

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
