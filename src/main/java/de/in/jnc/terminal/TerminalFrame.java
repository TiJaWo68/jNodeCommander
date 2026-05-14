package de.in.jnc.terminal;

import java.awt.BorderLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.JFrame;
import javax.swing.WindowConstants;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.ui.JediTermWidget;

/**
 * A standalone JFrame containing a JediTerm terminal widget connected to an SSH shell.
 * <p>
 * Opens at the default terminal size (80x24) and properly disconnects the SSH session
 * when the window is closed.
 * <p>
 * <em>Note:</em> This is an intermediate solution for Epic 3 Story 3.1. In future
 * iterations this will be refactored into a tabbed {@code ConnectionFrame} containing
 * terminal, file transfer, and browser tabs.
 */
public class TerminalFrame extends JFrame {

    private static final Logger LOGGER = LogManager.getLogger(TerminalFrame.class);

    private static final int DEFAULT_COLUMNS = 80;
    private static final int DEFAULT_ROWS = 24;

    private final transient JediTermWidget terminalWidget;
    private final transient SshConnection sshConnection;
    private final transient TtyConnector ttyConnector;

    /**
     * Creates a new terminal window and immediately starts the SSH connection.
     *
     * @param title         window title
     * @param sshConnection the SSH connection to use (must not be connected yet)
     */
    public TerminalFrame(String title, SshConnection sshConnection) {
        super(title);

        this.sshConnection = sshConnection;

        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());
        setSize(800, 600);
        setLocationRelativeTo(null);

        // Create JediTerm widget with Solarized Dark theme
        terminalWidget = new JediTermWidget(DEFAULT_COLUMNS, DEFAULT_ROWS,
                new SolarizedDarkSettingsProvider());

        add(terminalWidget, BorderLayout.CENTER);

        // Create the TtyConnector bridge
        ttyConnector = new SshTtyConnector(sshConnection);

        // Wire up and start the terminal session
        terminalWidget.setTtyConnector(ttyConnector);

        // Clean up on window close
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                stopTerminal();
            }
        });
    }

    /**
     * Returns the JediTerm widget for programmatic access.
     *
     * @return the terminal widget
     */
    public JediTermWidget getTerminalWidget() {
        return terminalWidget;
    }

    private void stopTerminal() {
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
        LOGGER.info("Terminal window closed for {}", sshConnection);
    }

    /**
     * Returns the underlying SSH connection.
     *
     * @return the SSH connection
     */
    public SshConnection getSshConnection() {
        return sshConnection;
    }
}
