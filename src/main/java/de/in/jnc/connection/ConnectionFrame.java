package de.in.jnc.connection;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import de.in.jnc.connection.browser.Bookmark;
import de.in.jnc.connection.browser.BrowserMenu;
import de.in.jnc.connection.browser.BrowserPanel;
import de.in.jnc.connection.browser.HistoryEntry;
import de.in.jnc.connection.browser.HistoryPanel;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.TerminalActionProvider;
import com.jediterm.terminal.ui.TerminalPanel;
import com.jediterm.terminal.ui.settings.SettingsProvider;

import de.in.jnc.ConnectionProfile;
import de.in.jnc.ProfileManager;
import de.in.jnc.connection.browser.BrowserTabManager;
import de.in.jnc.connection.browser.Endpoint;
import de.in.jnc.connection.browser.EndpointPopupMenu;
import de.in.jnc.connection.browser.K8sEndpointDiscoverer;
import de.in.jnc.connection.browser.PortForwardManager;
import de.in.jnc.connection.filetransfer.FileTransferPanel;
import de.in.jnc.connection.filetransfer.SftpService;
import de.in.jnc.terminal.CredentialsService;
import de.in.jnc.terminal.DynamicSettingsProvider;
import de.in.jnc.terminal.JncActionProvider;
import de.in.jnc.terminal.SshConnection;
import de.in.jnc.terminal.SshTtyConnector;
import de.in.jnc.terminal.TerminalSettings;
import de.in.jnc.terminal.TerminalSettingsPanel;

/**
 * A JFrame with a JTabbedPane containing a terminal tab and a file transfer tab.
 * <p>
 * Replaces the old {@code TerminalFrame}. Multiple parallel ConnectionFrame instances are allowed, each with its own SSH connection.
 * <p>
 * Tab layout:
 * <ul>
 * <li>Tab 0: Terminal (pinned, non-closable) — {@link JediTermWidget}</li>
 * <li>Tab 1: File Transfer (pinned, non-closable) — {@link FileTransferPanel}</li>
 * <li>Tab 2+: Browser tabs (dynamic, closable) — managed by {@link BrowserTabManager}</li>
 * </ul>
 */
public class ConnectionFrame extends JFrame {

	private static final Logger LOGGER = LogManager.getLogger(ConnectionFrame.class);

	private static final int DEFAULT_COLUMNS = 80;
	private static final int DEFAULT_ROWS = 24;

	private final JTabbedPane tabbedPane;
	private final SshConnection sshConnection;
	private final transient TerminalSettings terminalSettings;
	private final transient ConnectionProfile profile;

	private final transient JediTermWidget terminalWidget;
	private Runnable terminalRefreshAction;
	private final transient TtyConnector ttyConnector;
	private final transient SftpService sftpService;
	private final FileTransferPanel fileTransferPanel;
	private final BrowserTabManager browserTabManager;
	private final K8sEndpointDiscoverer endpointDiscoverer;
	private final PortForwardManager portForwardManager;
	private final EndpointPopupMenu endpointPopupMenu;
	private final CredentialsService credentialsService;
	private final BrowserMenu browserMenu;
	private JButton browserMenuBtn;

	/**
	 * Creates a new ConnectionFrame with the given SSH connection.
	 * <p>
	 * The SSH connection must already be established ({@link SshConnection#connect()} must have been called successfully).
	 *
	 * @param title         window title (e.g. "user@host")
	 * @param sshConnection the established SSH connection
	 * @param settings      terminal appearance settings
	 * @param profile       the connection profile for persisting state (e.g. last directories), or null
	 * @throws IOException if the SFTP channel cannot be opened
	 */
	public ConnectionFrame(String title, SshConnection sshConnection, TerminalSettings settings, ConnectionProfile profile) throws IOException {
		super(title);

		this.sshConnection = sshConnection;
		this.terminalSettings = settings;
		this.profile = profile;

		setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		setLayout(new BorderLayout());
		setSize(1000, 700);

		// Restore window position from profile, or centre on screen
		if (profile != null && profile.getWindowX() >= 0 && profile.getWindowY() >= 0 && profile.getWindowWidth() > 0 && profile.getWindowHeight() > 0) {
			Rectangle saved = new Rectangle(profile.getWindowX(), profile.getWindowY(), profile.getWindowWidth(), profile.getWindowHeight());
			// Ensure the saved bounds are visible on at least one screen
			if (isOnScreen(saved)) {
				setBounds(saved);
			} else {
				setLocationRelativeTo(null);
			}
		} else {
			setLocationRelativeTo(null);
		}

		// Set the application icon
		setIconImage(new FlatSVGIcon("jnc.svg", 32, 32).getImage());

		// --- Tabbed Pane ---
		tabbedPane = new JTabbedPane();
		tabbedPane.setUI(new ChromeTabbedPaneUI());
		tabbedPane.putClientProperty("JTabbedPane.showTabSeparators", Boolean.FALSE);
		tabbedPane.putClientProperty("JTabbedPane.hasFullBorder", Boolean.FALSE);
		tabbedPane.putClientProperty("JTabbedPane.minimumTabHeight", 22);

		// ── Tab 0: Terminal (pinned, icon-only) ────────────────────────
		DynamicSettingsProvider settingsProvider = new DynamicSettingsProvider(settings);
		terminalWidget = new JediTermWidget(DEFAULT_COLUMNS, DEFAULT_ROWS, settingsProvider) {
			@Override
			protected TerminalPanel createTerminalPanel(SettingsProvider sp, StyleState ss, TerminalTextBuffer buf) {
				// Custom TerminalPanel that adds "Settings..." at the very end of the context menu
				return new TerminalPanel(sp, buf, ss) {
					{
						// Capture refresh action within the TerminalPanel subclass,
						// where reinitFontAndResize() is accessible as a protected method
						ConnectionFrame.this.terminalRefreshAction = this::reinitFontAndResize;
					}

					@Override
					protected JPopupMenu createPopupMenu(TerminalActionProvider actionProvider) {
						JPopupMenu popup = super.createPopupMenu(actionProvider);
						popup.addSeparator();
						JMenuItem settingsItem = new JMenuItem("Settings...");
						settingsItem.setMnemonic(KeyEvent.VK_S);
						settingsItem.addActionListener(e -> showTerminalSettingsDialog());
						popup.add(settingsItem);
						return popup;
					}
				};
			}
		};
		ttyConnector = new SshTtyConnector(sshConnection);
		terminalWidget.setTtyConnector(ttyConnector);
		terminalWidget.getTerminalPanel().setDefaultCursorShape(settings.getEffectiveCursorShape());

		// ── Credentials Service (Story 4.2) ──────────────────────────────
		credentialsService = new CredentialsService();
		credentialsService.initialize(sshConnection);

		// ── Custom Context Menu: Credentials (Story 4.1) ───────────────
		JncActionProvider jncActionProvider = new JncActionProvider(credentialsService, this::insertTextAtCursor);
		terminalWidget.setNextProvider(jncActionProvider);

		FlatSVGIcon terminalIcon = new FlatSVGIcon("terminal.svg", 16, 16);
		tabbedPane.addTab(null, terminalIcon, terminalWidget, "SSH Terminal Session");

		// ── Tab 1: File Transfer (pinned, icon-only) ─────────────────
		sftpService = new SftpService(sshConnection.getSFTPClient());
		fileTransferPanel = new FileTransferPanel(sftpService, profile);

		FlatSVGIcon fileTransferIcon = new FlatSVGIcon("folder.svg", 16, 16);
		tabbedPane.addTab(null, fileTransferIcon, fileTransferPanel, "SFTP File Transfer");

		// ── Browser Tab Manager ──────────────────────────────────────────
		browserTabManager = new BrowserTabManager(tabbedPane);
		browserTabManager.setCredentialsCallback(valueInserter -> credentialsService.showCredentialsDialog(this, valueInserter));

		// ── Bookmark callback (Ctrl+D) ────────────────────────────────────
		browserTabManager.setBookmarkCallback(() -> {
			if (profile == null) return;
			String currentUrl = getCurrentBrowserUrl();
			if (currentUrl == null || currentUrl.isEmpty() || "about:blank".equals(currentUrl)) return;
			Bookmark bm = new Bookmark(currentUrl, currentUrl, null);
			List<Bookmark> list = profile.getBookmarks();
			if (!list.contains(bm)) {
				list.add(bm);
				persistProfile();
				LOGGER.info("Bookmark added: {} (total: {})", bm.getUrl(), list.size());
			}
		});

		// ── History callback ──────────────────────────────────────────────
		browserTabManager.setHistoryCallback(url -> {
			if (profile == null) return;
			String baseUrl = stripQueryParams(url);
			List<HistoryEntry> hist = profile.getHistory();
			boolean exists = hist.stream()
					.anyMatch(e -> stripQueryParams(e.getUrl()).equals(baseUrl));
			if (!exists) {
				hist.add(new HistoryEntry(url, baseUrl));
				persistProfile();
			}
		});

		// ── Web Apps Discovery (Story 3.3.2) ─────────────────────────────
		endpointDiscoverer = new K8sEndpointDiscoverer(sshConnection);
		portForwardManager = new PortForwardManager(sshConnection);
		endpointPopupMenu = new EndpointPopupMenu();

		// ── Browser Menu (Story 6.3) ──────────────────────────────────────
		browserMenu = new BrowserMenu(this);

		// ── Leading/Trailing components in tab strip (browser-style) ─────
		FlatSVGIcon browserMenuIcon = new FlatSVGIcon("web_apps_menu.svg", 16, 16);
		browserMenuBtn = new JButton(browserMenuIcon);
		browserMenuBtn.setToolTipText("Browser menu (Bookmarks, History, Tabs, Endpoints)");
		browserMenuBtn.addActionListener(e -> {
			browserMenu.rebuild();
			browserMenu.show(browserMenuBtn, 0, browserMenuBtn.getHeight());
		});
		tabbedPane.putClientProperty("JTabbedPane.leadingComponent", browserMenuBtn);

		JButton addTabBtn = new JButton("+");
		addTabBtn.setForeground(new Color(0xFF, 0xB7, 0x4D));
		addTabBtn.setToolTipText("New Browser Tab");
		addTabBtn.addActionListener(e -> openNewBrowserTab());
		tabbedPane.putClientProperty("JTabbedPane.trailingComponent", addTabBtn);

		add(tabbedPane, BorderLayout.CENTER);

		// ── Tab-switch auto-focus (Story 6.7) ──────────────────────────
		tabbedPane.addChangeListener(e -> {
			int idx = tabbedPane.getSelectedIndex();
			if (idx < 0) return;
			Component comp = tabbedPane.getComponentAt(idx);
			SwingUtilities.invokeLater(() -> {
				if (idx == 0) {
					// Terminal: focus JediTerm
					terminalWidget.getTerminalPanel().requestFocusInWindow();
				} else if (idx == 1) {
					// File Transfer: focus local panel
					fileTransferPanel.requestInitialFocus();
				} else if (comp instanceof BrowserPanel bp) {
					// Browser tab: focus URL bar
					bp.requestUrlBarFocus();
				} else if (comp instanceof HistoryPanel hp) {
					// History tab: refresh data
					hp.refresh();
				}
			});
		});

		// ── Global keyboard shortcuts (Story 6.4) ──────────────────────
		KeyEventDispatcher keyDispatcher = e -> {
			if (e.getID() != KeyEvent.KEY_PRESSED || !e.isAltDown()) {
				return false;
			}
			switch (e.getKeyCode()) {
				case KeyEvent.VK_C:
					tabbedPane.setSelectedIndex(0);
					return true;
				case KeyEvent.VK_N:
					tabbedPane.setSelectedIndex(1);
					return true;
				case KeyEvent.VK_1: case KeyEvent.VK_2: case KeyEvent.VK_3:
				case KeyEvent.VK_4: case KeyEvent.VK_5: case KeyEvent.VK_6:
				case KeyEvent.VK_7: case KeyEvent.VK_8: case KeyEvent.VK_9:
					int browserIdx = 2 + (e.getKeyCode() - KeyEvent.VK_1);
					if (browserIdx < tabbedPane.getTabCount()) {
						tabbedPane.setSelectedIndex(browserIdx);
					}
					return true;
				default:
					return false;
			}
		};
		KeyboardFocusManager.getCurrentKeyboardFocusManager()
				.addKeyEventDispatcher(keyDispatcher);

		// Clean up on window close
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosed(WindowEvent e) {
				closeConnection();
			}
		});

		// ── Session restore: re-open tabs from last session (Story 6.5) ──
		if (profile != null && profile.isRestoreTabs()
				&& !profile.getSavedTabUrls().isEmpty()) {
			List<String> urls = new ArrayList<>(profile.getSavedTabUrls());
			profile.setSavedTabUrls(new ArrayList<>()); // clear after restore
			SwingUtilities.invokeLater(() -> {
				for (String url : urls) {
					if (url != null && !url.isEmpty() && !"about:blank".equals(url)) {
						openBrowserUrl(url, url);
					}
				}
			});
		}
	}

	/**
	 * Starts the terminal emulator (JediTerm) and makes the window visible. Must be called on the Event Dispatch Thread.
	 */
	public void startTerminal() {
		terminalWidget.start();
	}

	/**
	 * Gracefully shuts down the terminal, SFTP service, and SSH connection. Also persists the current file transfer directories to the
	 * connection profile.
	 */
	private void closeConnection() {
		LOGGER.info("Closing ConnectionFrame for {}", sshConnection);

		// Stop all port-forward tunnels (Story 3.3.2)
		portForwardManager.stopAll();

		// Save current state into the profile before closing
		if (profile != null) {
			// Save open tab URLs for session restore (Story 6.5)
			if (profile.isRestoreTabs()) {
				profile.setSavedTabUrls(browserTabManager.getOpenUrls());
			} else {
				profile.setSavedTabUrls(new java.util.ArrayList<>());
			}
			// Save file transfer directories
			fileTransferPanel.saveDirectoriesToProfile();
			// Save window bounds
			profile.setWindowX(getX());
			profile.setWindowY(getY());
			profile.setWindowWidth(getWidth());
			profile.setWindowHeight(getHeight());
			persistProfile();
			LOGGER.debug("Persisted session state to profile '{}'", profile.getName());
		}

		// Close all browser tabs after saving URLs
		browserTabManager.closeAll();

		try {
			terminalWidget.close();
		} catch (Exception e) {
			LOGGER.warn("Error stopping terminal widget: {}", e.getMessage());
		}
		try {
			ttyConnector.close();
		} catch (Exception e) {
			LOGGER.warn("Error closing TtyConnector: {}", e.getMessage());
		}
		try {
			if (sftpService != null) {
				sftpService.close();
			}
		} catch (Exception e) {
			LOGGER.warn("Error closing SFTP service: {}", e.getMessage());
		}
		sshConnection.disconnect();
		LOGGER.info("ConnectionFrame closed for {}", sshConnection);
	}

	/**
	 * Checks whether the given rectangle is visible on at least one graphics device screen.
	 */
	private static boolean isOnScreen(Rectangle bounds) {
		for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
			if (device.getDefaultConfiguration().getBounds().intersects(bounds)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Opens the given URL in a browser tab.
	 * <p>
	 * Delegates to {@link BrowserTabManager#openUrl(String, String)}. Intended to be called from the Web Apps popup menu (Story 3.3.2) or
	 * programmatically.
	 *
	 * @param url         the URL to open
	 * @param displayName initial tab label
	 */
	public void openBrowserUrl(String url, String displayName) {
		browserTabManager.openUrl(url, displayName);
	}

	/**
	 * Opens a fresh empty browser tab.
	 */
	/**
	 * Opens a Swing panel as a closable tab (e.g. History).
	 */
	public void openPanelTab(String title, JPanel panel) {
		tabbedPane.addTab(title, panel);
		tabbedPane.setSelectedIndex(tabbedPane.getTabCount() - 1);
	}

	/**
	 * Selects an existing panel tab with the given title.
	 * @return true if an existing tab was found and selected
	 */
	public boolean selectExistingPanelTab(String title) {
		for (int i = 0; i < tabbedPane.getTabCount(); i++) {
			if (title.equals(tabbedPane.getTitleAt(i))) {
				tabbedPane.setSelectedIndex(i);
				return true;
			}
		}
		return false;
	}

	public void openNewBrowserTab() {
		browserTabManager.openNewTab();
	}

	/**
	 * Returns the {@link BrowserTabManager} for external access (e.g. Web Apps button).
	 */
	public BrowserTabManager getBrowserTabManager() {
		return browserTabManager;
	}

	/**
	 * Discovers Kubernetes web service endpoints and shows the Web Apps popup menu next to the Web Apps button.
	 */
	private void showEndpointPopup() {
		try {
			List<Endpoint> endpoints = endpointDiscoverer.discover();
			boolean groupByNs = endpointDiscoverer.getNamespaceCount() > 2;

			endpointPopupMenu.rebuild(endpoints, groupByNs, this::openNewBrowserTab, this::onEndpointClick, this::onToggleView,
					() -> SwingUtilities.invokeLater(this::showEndpointPopup));
			endpointPopupMenu.show(browserMenuBtn, 0, browserMenuBtn.getHeight());
		} catch (IOException e) {
			LOGGER.error("Failed to discover Kubernetes endpoints", e);
			// Show an error popup as fallback
			endpointPopupMenu.removeAll();
			endpointPopupMenu.add("❌  Discovery failed: " + e.getMessage());
			endpointPopupMenu.show(browserMenuBtn, 0, browserMenuBtn.getHeight());
		}
	}

	/**
	 * Handles a click on an endpoint in the Web Apps popup.
	 * <p>
	 * Depending on the {@link de.in.jnc.connection.browser.AccessType}:
	 * <ul>
	 * <li>{@code NODE_PORT} → opens the URL directly</li>
	 * <li>{@code TUNNEL_REQUIRED} → starts a kubectl port-forward tunnel, then opens the local tunnel URL</li>
	 * <li>{@code INGRESS} → opens the hostname URL directly</li>
	 * </ul>
	 */
	private void onEndpointClick(Endpoint ep) {
		try {
			switch (ep.accessType()) {
			case NODE_PORT, INGRESS -> {
				LOGGER.debug("Opening endpoint URL directly: {}", ep.url());
				openBrowserUrl(ep.url(), ep.displayName());
			}
			case TUNNEL_REQUIRED -> {
				String endpointId = ep.namespace() + "/" + ep.displayName();
				LOGGER.debug("Starting port-forward tunnel for: {}", endpointId);
				int localPort = portForwardManager.startTunnel(endpointId, ep.namespace(), ep.serviceName(), ep.port());
				String tunnelUrl = "http://localhost:" + localPort;
				openBrowserUrl(tunnelUrl, ep.displayName());
			}
			}
		} catch (IOException ex) {
			LOGGER.error("Failed to open endpoint {}: {}", ep.displayName(), ex.getMessage());
		}
	}

	/**
	 * Handles a toggle of the grouping mode in the Web Apps popup. Re-displays the popup with the new view mode.
	 *
	 * @param groupByNs {@code true} for grouped-by-namespace view, {@code false} for flat view
	 */
	private void onToggleView(boolean groupByNs) {
		SwingUtilities.invokeLater(() -> {
			try {
				List<Endpoint> endpoints = endpointDiscoverer.discover();
				endpointPopupMenu.rebuild(endpoints, groupByNs, this::openNewBrowserTab, this::onEndpointClick, this::onToggleView,
						() -> SwingUtilities.invokeLater(this::showEndpointPopup));
				endpointPopupMenu.show(browserMenuBtn, 0, browserMenuBtn.getHeight());
			} catch (IOException e) {
				LOGGER.error("Failed to re-discover endpoints after view toggle", e);
			}
		});
	}

	// ─── Story 4.1: Terminal Context Menu Extensions ────────────────────

	/**
	 * Writes the given text to the terminal's SSH shell channel, effectively inserting it at the cursor position.
	 */
	private void insertTextAtCursor(String text) {
		try {
			ttyConnector.write(text);
		} catch (IOException e) {
			LOGGER.error("Failed to insert text at cursor: {}", e.getMessage());
		}
	}

	/**
	 * Opens the per-profile terminal settings dialog (modal).
	 * <p>
	 * If the connection has an associated profile, the override is saved back to the profile. The terminal widget is <b>not</b> automatically
	 * refreshed — the settings take effect on the next connection.
	 */
	private void showTerminalSettingsDialog() {
		String profileName = (profile != null) ? profile.getName() : "Quick Connect";
		JDialog dialog = new JDialog(this, "Terminal Settings \u2013 " + profileName, true);
		dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
		dialog.setLayout(new BorderLayout());
		dialog.setSize(450, 350);
		dialog.setLocationRelativeTo(this);
		dialog.setIconImage(new FlatSVGIcon("gear.svg", 32, 32).getImage());

		TerminalSettingsPanel settingsPanel = new TerminalSettingsPanel(true);

		// If profile has a terminal settings override, pre-populate
		if (profile != null && profile.getTerminalSettingsOverride() != null) {
			settingsPanel.setSettings(profile.getTerminalSettingsOverride());
		}

		dialog.add(settingsPanel, BorderLayout.CENTER);

		// Button panel
		JPanel buttonPanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 10, 5));
		JButton saveBtn = new JButton("Save");
		JButton cancelBtn = new JButton("Cancel");

		saveBtn.addActionListener(e -> {
			TerminalSettings newSettings = settingsPanel.getSettings();
			if (profile != null) {
				profile.setTerminalSettingsOverride(newSettings);
				ProfileManager.getInstance().addOrUpdateProfile(profile);
				LOGGER.info("Per-profile terminal settings saved for '{}'", profile.getName());
			}

			// Apply settings to the running terminal immediately
			terminalSettings.setColorScheme(newSettings.getColorScheme());
			terminalSettings.setFontFamily(newSettings.getFontFamily());
			terminalSettings.setFontSize(newSettings.getFontSize());
			terminalSettings.setCursorShape(newSettings.getCursorShape());
			terminalSettings.setCursorBlinkRateMs(newSettings.getCursorBlinkRateMs());
			terminalSettings.setCustomForeground(newSettings.getCustomForeground());
			terminalSettings.setCustomBackground(newSettings.getCustomBackground());
			if (terminalRefreshAction != null) {
				terminalRefreshAction.run();
			}

			dialog.dispose();
		});

		cancelBtn.addActionListener(e -> dialog.dispose());

		buttonPanel.add(saveBtn);
		buttonPanel.add(cancelBtn);
		dialog.add(buttonPanel, BorderLayout.SOUTH);

		dialog.setVisible(true);
	}

	// ─── Getters ──────────────────────────────────────────────────────────

	public JediTermWidget getTerminalWidget() {
		return terminalWidget;
	}

	public FileTransferPanel getFileTransferPanel() {
		return fileTransferPanel;
	}

	public SftpService getSftpService() {
		return sftpService;
	}

	public SshConnection getSshConnection() {
		return sshConnection;
	}

	public JTabbedPane getTabbedPane() {
		return tabbedPane;
	}

	/**
	 * Returns the URL of the currently selected browser tab, or null.
	 */
	/** Strips query parameters and fragment from a URL. */
	/** Persists the current profile to disk (bookmarks, history, etc.). */
	private void persistProfile() {
		if (profile != null) {
			ProfileManager.getInstance().addOrUpdateProfile(profile);
			LOGGER.debug("Profile persisted: {} bookmarks, {} history entries",
					profile.getBookmarks().size(), profile.getHistory().size());
		}
	}

	private static String stripQueryParams(String url) {
		if (url == null) return "";
		int q = url.indexOf('?');
		int h = url.indexOf('#');
		int end = url.length();
		if (q >= 0) end = Math.min(end, q);
		if (h >= 0) end = Math.min(end, h);
		return url.substring(0, end);
	}

	private String getCurrentBrowserUrl() {
		int idx = tabbedPane.getSelectedIndex();
		if (idx < 2) return null;
		Component comp = tabbedPane.getComponentAt(idx);
		if (comp instanceof BrowserPanel bp) {
			return bp.getCurrentUrl();
		}
		return null;
	}

	/**
	 * Returns the connection profile, or {@code null} for quick-connect sessions.
	 */
	public ConnectionProfile getProfile() {
		return profile;
	}

	/**
	 * Triggers Kubernetes endpoint discovery and shows the popup menu.
	 * Called from {@link de.in.jnc.connection.browser.BrowserMenu}.
	 */
	public void clearHistory() {
		if (profile != null) {
			profile.getHistory().clear();
			ProfileManager.getInstance().addOrUpdateProfile(profile);
		}
	}

	public void showEndpointDiscovery() {
		showEndpointPopup();
	}
}

