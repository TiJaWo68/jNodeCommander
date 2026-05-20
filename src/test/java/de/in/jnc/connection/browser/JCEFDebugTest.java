package de.in.jnc.connection.browser;

import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.in.jnc.connection.browser.backend.BrowserBackend;
import de.in.jnc.connection.browser.backend.CertificateErrorHandler;
import de.in.jnc.connection.browser.backend.SslCertInfo;
import de.in.jnc.connection.browser.backend.jcef.JCEFBackend;
import de.in.jnc.connection.browser.backend.jcef.JCEFInitializer;

/**
 * Manual interactive test for the JCEF browser backend.
 * <p>
 * Run this <b>without</b> {@code -Djcef.native=true} – the JCEF native
 * libraries are loaded automatically from the jcefmaven Maven cache.
 * </p>
 * <p>
 * <b>Usage:</b>
 * <pre>{@code
 *   # Default URL (Google):
 *   mvn exec:java "-Dexec.mainClass=de.in.jnc.connection.browser.JCEFDebugTest"
 *
 *   # Custom URL (e.g. Keycloak):
 *   mvn exec:java "-Dexec.mainClass=de.in.jnc.connection.browser.JCEFDebugTest" "-Dexec.args=https://tbs10-plat1/auth"
 * }</pre>
 */
public final class JCEFDebugTest {

    private static final Logger LOGGER = LogManager.getLogger(JCEFDebugTest.class);

    private static final String DEFAULT_URL = "https://www.google.com";

    private final String initialUrl;

    private BrowserBackend backend;
    private JTextField urlField;
    private JFrame frame;

    // ── Entry point ────────────────────────────────────────────────────

    public static void main(String[] args) {
        String url = (args.length > 0) ? args[0] : DEFAULT_URL;
        SwingUtilities.invokeLater(() -> {
            try {
                new JCEFDebugTest(url).start();
            } catch (Exception e) {
                System.err.println("FATAL: " + e.getClass().getName() + ": " + e.getMessage());
                e.printStackTrace(System.err);
                System.exit(1);
            }
        });
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    JCEFDebugTest(String initialUrl) {
        this.initialUrl = initialUrl;
    }

    void start() throws Exception {
        // Initialise JCEF (loads native libraries from jcefmaven cache)
        JCEFInitializer.initialize();

        // Create backend
        backend = new JCEFBackend(initialUrl);

        // Wire listeners
        backend.setLocationListener(url -> {
            LOGGER.info("URL changed: {}", url);
            SwingUtilities.invokeLater(() -> urlField.setText(url));
        });
        backend.setTitleListener(title -> {
            LOGGER.info("Title changed: {}", title);
            SwingUtilities.invokeLater(() -> {
                if (frame != null) {
                    frame.setTitle("JCEF Debug – " + title);
                }
            });
        });
        backend.setCertificateErrorHandler(new DebugCertificateHandler());
        backend.setPopupHandler(url -> {
            LOGGER.info("Popup (would open new tab): {}", url);
            // In a real app we would open a new tab; here we just log it.
            // Uncomment to navigate directly:
            // backend.loadUrl(url);
        });

        buildUI();

        LOGGER.info("JCEFDebugTest started – browsing '{}'", initialUrl);
    }

    void shutdown() {
        LOGGER.info("Shutting down JCEFDebugTest...");
        if (backend != null) {
            try {
                backend.dispose();
            } catch (Exception e) {
                LOGGER.warn("Error disposing backend: {}", e.getMessage());
            }
        }
        try {
            JCEFInitializer.shutdown();
        } catch (Exception e) {
            LOGGER.warn("Error shutting down JCEF: {}", e.getMessage());
        }
        if (frame != null) {
            frame.dispose();
        }
        // Explicit exit to avoid non-zero code from shutdown hook errors
        System.exit(0);
    }

    // ── UI ─────────────────────────────────────────────────────────────

    private void buildUI() {
        frame = new JFrame("JCEF Debug – starting...");
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setSize(1200, 800);
        frame.setLocationRelativeTo(null);

        // ── Menu bar ────────────────────────────────────────────────
        JMenuBar menuBar = new JMenuBar();
        JMenu toolsMenu = new JMenu("Tools");
        List<Action> contextActions = backend.getContextMenuActions();
        for (Action action : contextActions) {
            toolsMenu.add(new JMenuItem(action));
        }
        menuBar.add(toolsMenu);
        frame.setJMenuBar(menuBar);

        // ── Navigation bar ──────────────────────────────────────────
        JPanel navBar = new JPanel(new BorderLayout(4, 0));

        JButton backBtn = new JButton("◀");
        backBtn.addActionListener(e -> backend.goBack());
        navBar.add(backBtn, BorderLayout.WEST);

        JButton forwardBtn = new JButton("▶");
        forwardBtn.addActionListener(e -> backend.goForward());
        navBar.add(forwardBtn, BorderLayout.EAST);

        urlField = new JTextField(initialUrl);
        urlField.addActionListener(e -> backend.loadUrl(urlField.getText()));
        navBar.add(urlField, BorderLayout.CENTER);

        JButton goBtn = new JButton("Go");
        goBtn.addActionListener(e -> backend.loadUrl(urlField.getText()));
        navBar.add(goBtn, BorderLayout.EAST);

        JButton reloadBtn = new JButton("↻");
        reloadBtn.addActionListener(e -> backend.reload());
        navBar.add(reloadBtn, BorderLayout.EAST);

        frame.add(navBar, BorderLayout.NORTH);

        // ── Browser view ────────────────────────────────────────────
        JComponent browserView = backend.getViewComponent();
        frame.add(browserView, BorderLayout.CENTER);

        // ── Status label (bottom) ───────────────────────────────────
        JLabel statusLabel = new JLabel("Backend: " + backend.getType());
        frame.add(statusLabel, BorderLayout.SOUTH);

        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                shutdown();
            }
        });

        frame.setVisible(true);
    }

    // ── Certificate error handler ──────────────────────────────────────

    private static final class DebugCertificateHandler
            implements CertificateErrorHandler {

        @Override
        public boolean onCertificateError(String errorCode, String url,
                SslCertInfo sslInfo) {
            LOGGER.warn("Certificate error [{}] for URL: {}  (host={})",
                    errorCode, url,
                    sslInfo != null ? sslInfo.getHostname() : "n/a");

            // Always accept in debug mode so we can test against HTTPS
            // servers with self-signed certificates.
            LOGGER.warn("DEBUG MODE – accepting certificate for: {}", url);
            return true;
        }
    }
}
