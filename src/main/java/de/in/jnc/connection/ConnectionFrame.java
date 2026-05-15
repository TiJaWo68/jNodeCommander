package de.in.jnc.connection;

import java.awt.BorderLayout;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;

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
import de.in.jnc.connection.filetransfer.FileTransferPanel;
import de.in.jnc.connection.filetransfer.SftpService;
import de.in.jnc.terminal.DynamicSettingsProvider;
import de.in.jnc.terminal.SshConnection;
import de.in.jnc.terminal.SshTtyConnector;
import de.in.jnc.terminal.TerminalSettings;

/**
 * A JFrame with a JTabbedPane containing a terminal tab and a file transfer tab.
 * <p>
 * Replaces the old {@code TerminalFrame}. Multiple parallel ConnectionFrame
 * instances are allowed, each with its own SSH connection.
 * <p>
 * Tab layout:
 * <ul>
 *   <li>Tab 0: Terminal (pinned, non-closable) — {@link JediTermWidget}</li>
 *   <li>Tab 1: File Transfer (pinned, non-closable) — {@link FileTransferPanel}</li>
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

    /**
     * Creates a new ConnectionFrame with the given SSH connection.
     * <p>
     * The SSH connection must already be established ({@link SshConnection#connect()}
     * must have been called successfully).
     *
     * @param title            window title (e.g. "user@host")
     * @param sshConnection    the established SSH connection
     * @param settings         terminal appearance settings
     * @param profile          the connection profile for persisting state (e.g. last directories), or null
     * @throws IOException     if the SFTP channel cannot be opened
     */
    public ConnectionFrame(String title, SshConnection sshConnection, TerminalSettings settings,
                           ConnectionProfile profile) throws IOException {
        super(title);

        this.sshConnection = sshConnection;
        this.terminalSettings = settings;
        this.profile = profile;

        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());
        setSize(1000, 700);

        // Restore window position from profile, or centre on screen
        if (profile != null && profile.getWindowX() >= 0 && profile.getWindowY() >= 0
                && profile.getWindowWidth() > 0 && profile.getWindowHeight() > 0) {
            Rectangle saved = new Rectangle(profile.getWindowX(), profile.getWindowY(),
                    profile.getWindowWidth(), profile.getWindowHeight());
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

        // ── Tab 0: Terminal ──────────────────────────────────────────────
        terminalWidget = new JediTermWidget(DEFAULT_COLUMNS, DEFAULT_ROWS,
                new DynamicSettingsProvider(settings));
        ttyConnector = new SshTtyConnector(sshConnection);
        terminalWidget.setTtyConnector(ttyConnector);
        terminalWidget.getTerminalPanel().setDefaultCursorShape(settings.getEffectiveCursorShape());

        FlatSVGIcon terminalIcon = new FlatSVGIcon("terminal.svg", 16, 16);
        tabbedPane.addTab("Terminal", terminalIcon, terminalWidget, "SSH Terminal Session");

        // ── Tab 1: File Transfer ─────────────────────────────────────────
        sftpService = new SftpService(sshConnection.getSFTPClient());
        fileTransferPanel = new FileTransferPanel(sftpService, profile);

        FlatSVGIcon folderIcon = new FlatSVGIcon("folder.svg", 16, 16);
        tabbedPane.addTab("File Transfer", folderIcon, fileTransferPanel, "SFTP File Transfer");

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
     * Starts the terminal emulator (JediTerm) and makes the window visible.
     * Must be called on the Event Dispatch Thread.
     */
    public void startTerminal() {
        terminalWidget.start();
    }

    /**
     * Gracefully shuts down the terminal, SFTP service, and SSH connection.
     * Also persists the current file transfer directories to the connection profile.
     */
    private void closeConnection() {
        LOGGER.info("Closing ConnectionFrame for {}", sshConnection);

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
