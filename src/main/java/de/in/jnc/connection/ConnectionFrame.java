package de.in.jnc.connection;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.ui.JediTermWidget;

import de.in.jnc.ConnectionProfile;
import de.in.jnc.ProfileManager;
import de.in.jnc.connection.browser.BrowserTabManager;
import de.in.jnc.connection.browser.Endpoint;
import de.in.jnc.connection.browser.EndpointPopupMenu;
import de.in.jnc.connection.browser.K8sEndpointDiscoverer;
import de.in.jnc.connection.browser.PortForwardManager;
import de.in.jnc.connection.filetransfer.FileTransferPanel;
import de.in.jnc.connection.filetransfer.SftpService;
import de.in.jnc.terminal.DynamicSettingsProvider;
import de.in.jnc.terminal.SshConnection;
import de.in.jnc.terminal.SshTtyConnector;
import de.in.jnc.terminal.TerminalSettings;

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
	private final transient TtyConnector ttyConnector;
	private final transient SftpService sftpService;
	private final FileTransferPanel fileTransferPanel;
	private final BrowserTabManager browserTabManager;
	private final K8sEndpointDiscoverer endpointDiscoverer;
	private final PortForwardManager portForwardManager;
	private final EndpointPopupMenu endpointPopupMenu;
	private JButton webAppsBtn;

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

		// ── Tab 0: Terminal (pinned, icon-only) ────────────────────────
		terminalWidget = new JediTermWidget(DEFAULT_COLUMNS, DEFAULT_ROWS, new DynamicSettingsProvider(settings));
		ttyConnector = new SshTtyConnector(sshConnection);
		terminalWidget.setTtyConnector(ttyConnector);
		terminalWidget.getTerminalPanel().setDefaultCursorShape(settings.getEffectiveCursorShape());

		FlatSVGIcon terminalIcon = new FlatSVGIcon("terminal.svg", 16, 16);
		tabbedPane.addTab(null, terminalIcon, terminalWidget, "SSH Terminal Session");

		// ── Tab 1: File Transfer (pinned, icon-only) ─────────────────
		sftpService = new SftpService(sshConnection.getSFTPClient());
		fileTransferPanel = new FileTransferPanel(sftpService, profile);

		FlatSVGIcon fileTransferIcon = new FlatSVGIcon("folder.svg", 16, 16);
		tabbedPane.addTab(null, fileTransferIcon, fileTransferPanel, "SFTP File Transfer");

		// ── Browser Tab Manager ──────────────────────────────────────────
		browserTabManager = new BrowserTabManager(tabbedPane);

		// ── Web Apps Discovery (Story 3.3.2) ─────────────────────────────
		endpointDiscoverer = new K8sEndpointDiscoverer(sshConnection);
		portForwardManager = new PortForwardManager(sshConnection);
		endpointPopupMenu = new EndpointPopupMenu();

		// ── Leading/Trailing components in tab strip (browser-style) ─────
		FlatSVGIcon webAppsIcon = new FlatSVGIcon("web_apps_menu.svg", 16, 16);
		webAppsBtn = new JButton(webAppsIcon);
		webAppsBtn.setToolTipText("Discover and open Kubernetes web services");
		webAppsBtn.addActionListener(e -> showEndpointPopup());
		tabbedPane.putClientProperty("JTabbedPane.leadingComponent", webAppsBtn);

		JButton addTabBtn = new JButton("+");
		addTabBtn.setForeground(new Color(0xFF, 0xB7, 0x4D));
		addTabBtn.setToolTipText("New Browser Tab");
		addTabBtn.addActionListener(e -> openNewBrowserTab());
		tabbedPane.putClientProperty("JTabbedPane.trailingComponent", addTabBtn);

		add(tabbedPane, BorderLayout.CENTER);

		// Clean up on window close
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosed(WindowEvent e) {
				closeConnection();
			}
		});
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

		// Close all browser tabs and release JFX resources
		browserTabManager.closeAll();

		// Save current state into the profile before closing
		if (profile != null) {
			// Save file transfer directories
			fileTransferPanel.saveDirectoriesToProfile();
			// Save window bounds
			profile.setWindowX(getX());
			profile.setWindowY(getY());
			profile.setWindowWidth(getWidth());
			profile.setWindowHeight(getHeight());
			ProfileManager.getInstance().addOrUpdateProfile(profile);
			LOGGER.debug("Persisted window bounds and file transfer directories to profile '{}'", profile.getName());
		}

		try {
			terminalWidget.stop();
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
			endpointPopupMenu.show(webAppsBtn, 0, webAppsBtn.getHeight());
		} catch (IOException e) {
			LOGGER.error("Failed to discover Kubernetes endpoints", e);
			// Show an error popup as fallback
			endpointPopupMenu.removeAll();
			endpointPopupMenu.add("❌  Discovery failed: " + e.getMessage());
			endpointPopupMenu.show(webAppsBtn, 0, webAppsBtn.getHeight());
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
				endpointPopupMenu.show(webAppsBtn, 0, webAppsBtn.getHeight());
			} catch (IOException e) {
				LOGGER.error("Failed to re-discover endpoints after view toggle", e);
			}
		});
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
}
