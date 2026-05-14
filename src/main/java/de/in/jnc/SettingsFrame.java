package de.in.jnc;

import java.awt.BorderLayout;
import java.awt.FlowLayout;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.formdev.flatlaf.extras.FlatSVGIcon;

import de.in.jnc.terminal.TerminalSettingsPanel;

/**
 * A tabbed settings dialog accessible from the system tray menu.
 * <p>
 * Currently contains only the "Terminal" tab; more tabs (General, Security, etc.)
 * will be added in future Epics.
 * </p>
 */
public class SettingsFrame extends JFrame {

    private static final Logger LOGGER = LogManager.getLogger(SettingsFrame.class);

    private final TerminalSettingsPanel terminalPanel;

    /**
     * Creates the settings frame and loads current global settings.
     */
    public SettingsFrame() {
        setTitle("Settings \u2013 jNodeCommander");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(500, 380);
        setLocationRelativeTo(null);
        setResizable(false);
        setIconImage(new FlatSVGIcon("jnc.svg", 32, 32).getImage());

        JTabbedPane tabbedPane = new JTabbedPane();

        // Terminal settings tab
        terminalPanel = new TerminalSettingsPanel(false); // global mode
        terminalPanel.setSettings(GlobalSettings.getInstance().getTerminalSettings());
        tabbedPane.addTab("Terminal", terminalPanel);

        // Placeholder for future tabs
        JPanel placeholderPanel = new JPanel();
        placeholderPanel.add(new javax.swing.JLabel("More settings coming soon..."));
        tabbedPane.addTab("General", placeholderPanel);

        add(tabbedPane, BorderLayout.CENTER);

        // Bottom button bar
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 5));
        JButton saveBtn = new JButton("Save");
        JButton cancelBtn = new JButton("Cancel");

        saveBtn.addActionListener(e -> onSave());
        cancelBtn.addActionListener(e -> dispose());

        buttonPanel.add(saveBtn);
        buttonPanel.add(cancelBtn);
        add(buttonPanel, BorderLayout.SOUTH);

        getRootPane().setDefaultButton(saveBtn);
    }

    private void onSave() {
        GlobalSettings.getInstance().setTerminalSettings(terminalPanel.getSettings());
        LOGGER.info("Global terminal settings saved");
        dispose();
    }
}
