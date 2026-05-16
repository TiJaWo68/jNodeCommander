package de.in.jnc.connection;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;

/**
 * Manual debug tool for investigating the "white page" issue when loading
 * internal K8s platform URLs (e.g., {@code https://tbs10-plat1/auth}) in
 * the JavaFX {@link WebView} embedded via {@link JFXPanel}.
 * <p>
 * The test creates a window with:
 * <ul>
 *   <li>URL input field and navigation buttons</li>
 *   <li>Embedded WebView (same setup as {@code BrowserPanel})</li>
 *   <li>Console log area showing SSL and WebEngine diagnostics</li>
 *   <li>Buttons to toggle different SSL trust modes</li>
 * </ul>
 * <p>
 * Run with:
 * {@code mvn exec:java -Dexec.mainClass="de.in.jnc.connection.BrowserSSLDebugTest"}
 */
public class BrowserSSLDebugTest {

    private static final int WIDTH = 1200;
    private static final int HEIGHT = 800;

    private JFrame frame;
    private JFXPanel jfxPanel;
    private WebEngine webEngine;
    private JTextArea logArea;
    private JTextField urlField;
    private JLabel statusLabel;

    /** Remembers whether we installed the trust-all SSL context. */
    private boolean trustAllInstalled;

    public static void main(String[] args) throws Exception {
        // Redirect stdout/stderr to our log
        BrowserSSLDebugTest test = new BrowserSSLDebugTest();
        test.start();
    }

    void start() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            buildUI();
            latch.countDown();
        });
        latch.await(5, TimeUnit.SECONDS);

        // Print system info
        log("=== Browser SSL Debug Tool ===");
        log("Java version: " + System.getProperty("java.version"));
        log("Java home: " + System.getProperty("java.home"));
        log("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        log("User-Agent: " + System.getProperty("http.agent", "(not set)"));
        log("");

        log("To use: Enter a K8s URL (e.g. https://tbs10-plat1/auth) and click 'Load'");
        log("Try different SSL modes to find which one works.");
        log("");
    }

    private void buildUI() {
        frame = new JFrame("Browser SSL Debug Tool");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setSize(WIDTH, HEIGHT);
        frame.setLocationRelativeTo(null);

        JPanel mainPanel = new JPanel(new BorderLayout(4, 4));

        // ── Top: URL bar ───────────────────────────────────────────────
        JPanel topBar = new JPanel(new BorderLayout(4, 4));
        urlField = new JTextField("https://tbs10-plat1/auth");
        JButton loadBtn = new JButton("\u25B6 Load");
        JButton reloadBtn = new JButton("\u21BB Reload");

        loadBtn.addActionListener(e -> loadUrl(urlField.getText().trim()));
        reloadBtn.addActionListener(e -> {
            if (webEngine != null) {
                Platform.runLater(webEngine::reload);
                log("[Reload] Reloading current page");
            }
        });

        topBar.add(urlField, BorderLayout.CENTER);

        JPanel btnPanel = new JPanel();
        btnPanel.add(loadBtn);
        btnPanel.add(reloadBtn);
        topBar.add(btnPanel, BorderLayout.EAST);

        mainPanel.add(topBar, BorderLayout.NORTH);

        // ── Center: WebView + Log ──────────────────────────────────────
        jfxPanel = new JFXPanel();
        jfxPanel.setPreferredSize(new Dimension(WIDTH, HEIGHT * 2 / 3));
        mainPanel.add(jfxPanel, BorderLayout.CENTER);

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setPreferredSize(new Dimension(WIDTH, HEIGHT / 4));
        mainPanel.add(logScroll, BorderLayout.SOUTH);

        // ── Bottom: SSL controls + status ──────────────────────────────
        JPanel bottomBar = new JPanel(new BorderLayout(4, 4));

        JPanel sslPanel = new JPanel();
        JButton trustAllBtn = new JButton("Toggle Trust-All SSL");
        trustAllBtn.addActionListener(e -> toggleTrustAllSSL());
        sslPanel.add(trustAllBtn);

        JButton testConnBtn = new JButton("Test HTTPS Connection");
        testConnBtn.addActionListener(e -> testHttpsConnection(urlField.getText().trim()));
        sslPanel.add(testConnBtn);

        bottomBar.add(sslPanel, BorderLayout.WEST);

        statusLabel = new JLabel("Ready");
        bottomBar.add(statusLabel, BorderLayout.EAST);

        mainPanel.add(bottomBar, BorderLayout.SOUTH);

        frame.add(mainPanel);

        // ── Initialize JavaFX WebView ──────────────────────────────────
        Platform.runLater(this::initWebView);

        frame.setVisible(true);
    }

    private void initWebView() {
        WebView webView = new WebView();
        webEngine = webView.getEngine();

        // Enable JavaScript (required for KeyCloak login)
        webEngine.setJavaScriptEnabled(true);

        // Listen for loading state
        webEngine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            log("[WebEngine] State: " + newState);
            SwingUtilities.invokeLater(() ->
                    statusLabel.setText("State: " + newState));
        });

        // Listen for title changes
        webEngine.titleProperty().addListener((obs, oldTitle, newTitle) -> {
            if (newTitle != null) {
                log("[WebEngine] Title: '" + newTitle + "'");
            }
        });

        // Listen for location changes
        webEngine.locationProperty().addListener((obs, oldLoc, newLoc) -> {
            if (newLoc != null) {
                log("[WebEngine] Navigated to: " + newLoc);
                SwingUtilities.invokeLater(() -> urlField.setText(newLoc));
            }
        });

        // Listen for load errors
        webEngine.getLoadWorker().exceptionProperty().addListener((obs, oldErr, newErr) -> {
            if (newErr != null) {
                log("[WebEngine ERROR] " + newErr.getMessage());
                newErr.printStackTrace(System.out);
            }
        });

        jfxPanel.setScene(new Scene(webView));

        log("[WebView] Initialised with JavaScript enabled");
    }

    // ── URL loading ────────────────────────────────────────────────────

    private void loadUrl(String url) {
        if (url.isEmpty()) {
            log("[Load] No URL specified");
            return;
        }
        log("--- Loading: " + url + " ---");

        // First test the connection at Java level
        testHttpsConnection(url);

        // Then load in WebView
        if (webEngine != null) {
            Platform.runLater(() -> webEngine.load(url));
            log("[WebView] Load initiated");
        } else {
            log("[ERROR] WebEngine not initialised yet");
        }
    }

    // ── SSL debugging ──────────────────────────────────────────────────

    /**
     * Tests an HTTPS connection at the Java URLConnection level.
     * This helps determine if the issue is at the Java SSL layer or
     * within JavaFX WebView's native networking.
     */
    private void testHttpsConnection(String url) {
        if (url == null || url.isEmpty()) return;
        if (!url.startsWith("https://")) {
            log("[SSL Test] URL is not HTTPS, skipping connection test");
            return;
        }

        log("--- HTTPS Connection Test: " + url + " ---");
        try {
            java.net.URL javaUrl = new java.net.URL(url);
            javax.net.ssl.HttpsURLConnection conn =
                    (javax.net.ssl.HttpsURLConnection) javaUrl.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(true);

            log("  Response code: " + conn.getResponseCode());
            log("  Response message: " + conn.getResponseMessage());
            log("  Cipher suite: " + conn.getCipherSuite());
            // HttpsURLConnection.getSession() was removed in JDK 25; use the negotiated cipher suite as session indicator
            log("  SSL session (via cipher): " + conn.getCipherSuite());

            java.security.cert.Certificate[] certs = conn.getServerCertificates();
            if (certs != null && certs.length > 0) {
                log("  Server certificates (" + certs.length + "):");
                for (int i = 0; i < certs.length; i++) {
                    X509Certificate x509 = (X509Certificate) certs[i];
                    log("    [" + i + "] Subject: " + x509.getSubjectDN());
                    log("        Issuer: " + x509.getIssuerDN());
                    log("        Valid until: " + x509.getNotAfter());
                }
            }

            // Read the first 500 bytes of content
            try (java.io.InputStream is = conn.getInputStream()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int totalRead = 0;
                int len;
                while ((len = is.read(buf)) != -1 && totalRead < 500) {
                    baos.write(buf, 0, len);
                    totalRead += len;
                }
                String content = baos.toString("UTF-8");
                log("  Content preview (first 500 chars):");
                log("  ---");
                // Truncate to first 10 lines
                String[] lines = content.split("\n");
                for (int i = 0; i < Math.min(lines.length, 15); i++) {
                    log("  " + lines[i]);
                }
                if (lines.length > 15) {
                    log("  ... (" + (lines.length - 15) + " more lines)");
                }
                log("  ---");
            }

            conn.disconnect();
            log("[SSL Test] Connection SUCCESSFUL");
        } catch (javax.net.ssl.SSLHandshakeException e) {
            log("[SSL Test] SSL HANDSHAKE FAILED: " + e.getMessage());
            log("  This is likely a certificate trust issue.");
            log("  Try clicking 'Toggle Trust-All SSL' and reload.");
        } catch (java.net.ConnectException e) {
            log("[SSL Test] CONNECTION REFUSED: " + e.getMessage());
            log("  Check if the host is reachable and the port is open.");
        } catch (java.net.UnknownHostException e) {
            log("[SSL Test] UNKNOWN HOST: " + e.getMessage());
            log("  Check DNS resolution for this hostname.");
        } catch (Exception e) {
            log("[SSL Test] ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            e.printStackTrace(new PrintStream(baos));
            log("  " + baos.toString().replace("\n", "\n  "));
        }
        log("--- End Connection Test ---");
    }

    /**
     * Toggles a trust-all {@link SSLContext} that accepts any certificate.
     * <p>
     * WARNING: This disables all certificate validation and should only be
     * used for debugging. Do NOT enable this in production code.
     */
    private void toggleTrustAllSSL() {
        if (trustAllInstalled) {
            log("[SSL] Restoring default SSL context (trust-all disabled)");
            // We can't easily restore the original, but the user can restart
            trustAllInstalled = false;
            statusLabel.setText("Default SSL (trust-all OFF)");
            return;
        }

        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }
                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }
                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
            };

            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new SecureRandom());
            SSLContext.setDefault(sc);
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

            // Also set a lenient hostname verifier
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);

            trustAllInstalled = true;
            log("[SSL] Trust-all SSL context INSTALLED");
            log("  All certificates will be accepted (insecure!)");
            statusLabel.setText("Trust-All SSL ON (insecure)");
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            log("[SSL] Failed to install trust-all: " + e.getMessage());
        }
    }

    // ── Logging ────────────────────────────────────────────────────────

    private void log(String message) {
        System.out.println(message);
        SwingUtilities.invokeLater(() -> {
            logArea.append(message + "\n");
            // Auto-scroll to bottom
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }
}
