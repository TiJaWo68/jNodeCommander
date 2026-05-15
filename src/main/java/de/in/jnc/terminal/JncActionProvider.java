package de.in.jnc.terminal;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.SwingUtilities;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.jediterm.terminal.ui.TerminalAction;
import com.jediterm.terminal.ui.TerminalActionPresentation;
import com.jediterm.terminal.ui.TerminalActionProvider;

/**
 * A custom {@link TerminalActionProvider} that adds a <b>Credentials...</b>
 * entry to the JediTerm context menu (appears above "Find").
 * <p>
 * The credentials flow is two-step:
 * <ol>
 *   <li>Run {@code show_credentials} on the remote host to list available names.</li>
 *   <li>On name selection, run {@code sudo show_credentials <name>} and display
 *       the returned username / password for click-to-insert at the cursor position.</li>
 * </ol>
 * <p>
 * Set this as the {@code nextProvider} on {@code JediTermWidget} (after
 * {@code TerminalPanel}) so that its actions appear <b>before</b> the
 * built-in "Find" item (due to the reversal in {@link TerminalAction#buildMenu}).
 * The "Settings..." entry is added at the very end of the menu by overriding
 * {@link TerminalPanel#createPopupMenu} in {@code ConnectionFrame}.
 */
public class JncActionProvider implements TerminalActionProvider {

    private static final Logger LOGGER = LogManager.getLogger(JncActionProvider.class);

    private final SshConnection sshConnection;
    private final Consumer<String> textInserter;

    /**
     * Creates a new JncActionProvider.
     *
     * @param sshConnection the SSH connection (for running {@code show_credentials})
     * @param textInserter  callback to insert text at the terminal cursor position
     */
    public JncActionProvider(SshConnection sshConnection, Consumer<String> textInserter) {
        this.sshConnection = sshConnection;
        this.textInserter = textInserter;
    }

    @Override
    public List<TerminalAction> getActions() {
        return List.of(
                new TerminalAction(new TerminalActionPresentation("Credentials...", Collections.emptyList()), e -> {
                    onCredentials();
                    return true;
                }).withMnemonicKey(KeyEvent.VK_C)
        );
    }

    @Override
    public TerminalActionProvider getNextProvider() {
        return null;
    }

    @Override
    public void setNextProvider(TerminalActionProvider provider) {
        // Not needed – this is the terminal provider in the chain
    }

    // ── Credentials flow ─────────────────────────────────────────────────

    /**
     * Runs {@code show_credentials} to discover credential names, pre-fetches
     * each via {@code sudo show_credentials <name>}, and shows all results in
     * a single 3-column dialog (Credential | Username | Password).
     * <p>
     * The SSH connection's own credentials are shown as the first row.
     * Clicking a username or password cell inserts that value at the terminal
     * cursor position and closes the dialog.
     */
    private void onCredentials() {
        try {
            // show_credentials exits with status 1 when called without arguments
            // (it treats this as a usage error), but still outputs the credential
            // list to stdout. Use executeCommandLenient to capture the output.
            String output = sshConnection.executeCommandLenient("show_credentials");
            List<String> names = parseCredentialNames(output);

            // Pre-fetch all credentials via sudo show_credentials <name>
            Map<String, Credential> allCredentials = new LinkedHashMap<>();

            // SSH connection credentials (always first)
            String connUser = sshConnection.getUser();
            String connPass = sshConnection.getPassword();
            allCredentials.put("SSH Connection",
                    new Credential(connUser, connPass != null ? connPass : ""));

            for (String name : names) {
                try {
                    String credOutput = sshConnection.executeCommand(
                            "show_credentials " + name, true);
                    Credential cred = parseUserPassword(credOutput);
                    if (cred != null) {
                        allCredentials.put(name, cred);
                    }
                } catch (IOException e) {
                    LOGGER.warn("Failed to fetch credential '{}': {}", name, e.getMessage());
                }
            }

            showCredentialsDialog(allCredentials);
        } catch (IOException e) {
            LOGGER.error("Failed to execute show_credentials: {}", e.getMessage());
        }
    }

    /**
     * Displays a non-modal dialog with a 3-column table of credentials:
     * {@code Credential | Username | Password}.
     * <p>
     * Username and password cells are clickable — clicking one inserts the
     * value at the terminal cursor and closes the dialog.
     */
    private void showCredentialsDialog(Map<String, Credential> credentials) {
        JDialog dialog = new JDialog();
        dialog.setTitle("Credentials");
        dialog.setModal(false);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(2, 8, 2, 8);
        gbc.anchor = GridBagConstraints.WEST;

        // ── Header row ───────────────────────────────────────────────────
        gbc.gridy = 0;
        gbc.gridx = 0;
        gbc.weightx = 0.3;
        JLabel headerCred = new JLabel("Credential");
        headerCred.setFont(headerCred.getFont().deriveFont(Font.BOLD));
        panel.add(headerCred, gbc);

        gbc.gridx = 1;
        gbc.weightx = 0.35;
        JLabel headerUser = new JLabel("Username");
        headerUser.setFont(headerUser.getFont().deriveFont(Font.BOLD));
        panel.add(headerUser, gbc);

        gbc.gridx = 2;
        gbc.weightx = 0.35;
        JLabel headerPass = new JLabel("Password");
        headerPass.setFont(headerPass.getFont().deriveFont(Font.BOLD));
        panel.add(headerPass, gbc);

        // ── Separator ────────────────────────────────────────────────────
        gbc.gridy = 1;
        gbc.gridx = 0;
        gbc.gridwidth = 3;
        gbc.weightx = 1.0;
        panel.add(new JSeparator(), gbc);
        gbc.gridwidth = 1;

        // ── Data rows ────────────────────────────────────────────────────
        int row = 2;
        for (Map.Entry<String, Credential> entry : credentials.entrySet()) {
            String name = entry.getKey();
            Credential cred = entry.getValue();

            gbc.gridy = row;
            gbc.gridx = 0;
            gbc.weightx = 0.3;
            JLabel nameLabel = new JLabel(name);
            nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD));
            panel.add(nameLabel, gbc);

            // Username cell (clickable)
            gbc.gridx = 1;
            gbc.weightx = 0.35;
            JLabel userLabel = new JLabel(cred.username());
            userLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            userLabel.setToolTipText("Click to insert username");
            userLabel.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    textInserter.accept(cred.username());
                    dialog.dispose();
                }
            });
            panel.add(userLabel, gbc);

            // Password cell (clickable, masked)
            gbc.gridx = 2;
            gbc.weightx = 0.35;
            String masked = cred.password().isEmpty() ? "" : maskPassword(cred.password());
            JLabel passLabel = new JLabel(masked);
            passLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            passLabel.setToolTipText("Click to insert password");
            passLabel.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    textInserter.accept(cred.password());
                    dialog.dispose();
                }
            });
            panel.add(passLabel, gbc);

            row++;
        }

        int visibleRows = credentials.size();
        int prefHeight = Math.min(visibleRows * 24 + 40, 400);
        JScrollPane scrollPane = new JScrollPane(panel);
        scrollPane.setPreferredSize(new java.awt.Dimension(480, prefHeight));
        dialog.add(scrollPane, BorderLayout.CENTER);

        dialog.pack();
        Component invoker = SwingUtilities.getWindowAncestor(
                KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
        if (invoker != null) {
            dialog.setLocationRelativeTo(invoker);
        }
        dialog.setVisible(true);
    }

    // ── Parsing ──────────────────────────────────────────────────────────

    /**
     * Parses the output of {@code show_credentials} (first call) to extract
     * the list of available credential names.
     * <p>
     * Expected format:
     * <pre>
     * Usage: /usr/bin/show_credentials <credential>
     *
     * Supported credentials
     *     admin          KeyCloak super user ...
     *     du_admin       Admin user for ...
     * </pre>
     * Lines after "Supported credentials" are scanned; the first word on each
     * non-empty, non-indented line after the header is taken as a credential name.
     *
     * @param output the raw output of the first {@code show_credentials} call
     * @return list of credential names (never null)
     */
    static List<String> parseCredentialNames(String output) {
        List<String> names = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return names;
        }

        boolean inCredentialsSection = false;
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // Detect the "Supported credentials" header
            if (!inCredentialsSection && trimmed.toLowerCase().startsWith("supported credential")) {
                inCredentialsSection = true;
                continue;
            }
            if (inCredentialsSection) {
                // Skip lines that look like usage or continuation
                if (trimmed.startsWith("Usage:") || trimmed.startsWith("usage:")) {
                    continue;
                }
                // Take the first word as the credential name
                String[] parts = trimmed.split("\\s+");
                if (parts.length > 0 && !parts[0].isEmpty()) {
                    names.add(parts[0]);
                }
            }
        }
        return names;
    }

    /**
     * Parses the output of {@code sudo show_credentials <name>} (second call)
     * to extract the username and password.
     * <p>
     * Expected format:
     * <pre>
     * User: du_admin
     * Password: fYCXwS84QnUf
     * </pre>
     *
     * @param output the raw command output
     * @return a {@link Credential} record, or {@code null} if parsing fails
     */
    static Credential parseUserPassword(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }

        String username = null;
        String password = null;

        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            int colonIdx = trimmed.indexOf(':');
            if (colonIdx > 0) {
                String key = trimmed.substring(0, colonIdx).trim().toLowerCase();
                String value = trimmed.substring(colonIdx + 1).trim();
                switch (key) {
                    case "user" -> username = value;
                    case "password" -> password = value;
                    default -> { /* ignore */ }
                }
            }
        }

        if (username != null || password != null) {
            return new Credential(
                    username != null ? username : "",
                    password != null ? password : "");
        }
        return null;
    }

    private static String maskPassword(String password) {
        if (password == null || password.isEmpty()) {
            return "";
        }
        return "\u2022".repeat(Math.min(password.length(), 8));
    }

    // ── Credential record ────────────────────────────────────────────────

    /**
     * A resolved credential with username and password.
     *
     * @param username the username
     * @param password the password
     */
    public record Credential(String username, String password) {
    }
}
