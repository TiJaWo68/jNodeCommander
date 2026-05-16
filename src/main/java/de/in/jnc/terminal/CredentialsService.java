package de.in.jnc.terminal;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
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

/**
 * Instance-based service that fetches, caches, and presents SSH host
 * credentials retrieved via the remote {@code show_credentials} command.
 * <p>
 * Designed to be created <b>once per connection</b> and shared across
 * all consumers (terminal context menu, browser context menu, endpoint
 * popup menu).
 * <p>
 * Usage:
 * <ol>
 *   <li>Create an instance.</li>
 *   <li>Call {@link #initialize(SshConnection)} once after the SSH
 *       connection is established.</li>
 *   <li>Call {@link #showCredentialsDialog(Component, Consumer)} from
 *       any context that needs to display credentials.</li>
 * </ol>
 */
public class CredentialsService {

    private static final Logger LOGGER = LogManager.getLogger(CredentialsService.class);

    private static final int MAX_MASK_LENGTH = 8;
    private static final int DIALOG_WIDTH = 480;
    private static final int ROW_HEIGHT = 24;
    private static final int HEADER_PADDING = 40;
    private static final int MAX_DIALOG_HEIGHT = 400;
    private static final int PANEL_PADDING_TOP = 5;
    private static final int PANEL_PADDING_LEFT = 8;
    private static final int PANEL_PADDING_BOTTOM = 5;
    private static final int PANEL_PADDING_RIGHT = 8;
    private static final int CELL_INSET = 8;
    private static final int CELL_INSET_VERTICAL = 2;
    private static final double WEIGHT_NAME_COLUMN = 0.3;
    private static final double WEIGHT_VALUE_COLUMN = 0.35;

    private static final String SSH_CONNECTION_LABEL = "SSH Connection";

    private Map<String, Credential> cache = Collections.emptyMap();
    private boolean available;

    /**
     * A resolved credential with username and password.
     *
     * @param username the username
     * @param password the password
     */
    public record Credential(String username, String password) {
    }

    /**
     * Fetches all credentials from the remote host via
     * {@code show_credentials} and caches them. Must be called once
     * after the SSH connection is established.
     * <p>
     * The SSH connection's own credentials are always included as
     * the first entry ("SSH Connection").
     *
     * @param sshConnection the established SSH connection
     */
    public void initialize(SshConnection sshConnection) {
        try {
            String output = sshConnection.executeCommandLenient("show_credentials");
            List<String> names = parseCredentialNames(output);

            Map<String, Credential> allCredentials = new LinkedHashMap<>();

            String connUser = sshConnection.getUser();
            String connPass = sshConnection.getPassword();
            allCredentials.put(SSH_CONNECTION_LABEL,
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

            this.cache = Collections.unmodifiableMap(allCredentials);
            this.available = true;
            LOGGER.info("Credentials initialized: {} entries cached", cache.size());
        } catch (IOException e) {
            LOGGER.error("Failed to initialize credentials: {}", e.getMessage());
            this.available = false;
        }
    }

    /**
     * Returns whether credentials have been successfully loaded and
     * are available for display.
     *
     * @return {@code true} if {@link #initialize(SshConnection)} completed
     *         successfully and at least the SSH connection credential exists
     */
    public boolean isAvailable() {
        return available;
    }

    /**
     * Shows a non-modal credentials dialog using cached data.
     * <p>
     * The dialog displays a 3-column table (Credential | Username | Password).
     * Clicking a username or password cell invokes the {@code valueConsumer}
     * with the selected value and closes the dialog.
     *
     * @param parentComponent used for dialog positioning (may be {@code null})
     * @param valueConsumer   callback invoked with the selected credential value;
     *                        for the terminal this inserts at the cursor, for the
     *                        browser this triggers JS injection into the active
     *                        input field
     */
    public void showCredentialsDialog(Component parentComponent,
                                      Consumer<String> valueConsumer) {
        if (!available || cache.isEmpty()) {
            LOGGER.warn("Credentials dialog requested but no credentials available");
            return;
        }

        JDialog dialog = new JDialog();
        dialog.setTitle("Credentials");
        dialog.setModal(false);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(
                PANEL_PADDING_TOP, PANEL_PADDING_LEFT,
                PANEL_PADDING_BOTTOM, PANEL_PADDING_RIGHT));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(CELL_INSET_VERTICAL, CELL_INSET,
                CELL_INSET_VERTICAL, CELL_INSET);
        gbc.anchor = GridBagConstraints.WEST;

        addHeaderRow(panel, gbc);
        addSeparator(panel, gbc);
        addDataRows(panel, gbc, dialog, valueConsumer);

        int visibleRows = cache.size();
        int prefHeight = Math.min(visibleRows * ROW_HEIGHT + HEADER_PADDING,
                MAX_DIALOG_HEIGHT);
        JScrollPane scrollPane = new JScrollPane(panel);
        scrollPane.setPreferredSize(new java.awt.Dimension(DIALOG_WIDTH, prefHeight));
        dialog.add(scrollPane, BorderLayout.CENTER);

        dialog.pack();
        Component invoker = parentComponent != null
                ? parentComponent
                : SwingUtilities.getWindowAncestor(
                        KeyboardFocusManager.getCurrentKeyboardFocusManager()
                                .getFocusOwner());
        if (invoker != null) {
            dialog.setLocationRelativeTo(invoker);
        }
        dialog.setVisible(true);
    }

    // ── Dialog construction helpers ─────────────────────────────────────

    private static void addHeaderRow(JPanel panel, GridBagConstraints gbc) {
        gbc.gridy = 0;
        gbc.gridx = 0;
        gbc.weightx = WEIGHT_NAME_COLUMN;
        JLabel headerCred = new JLabel("Credential");
        headerCred.setFont(headerCred.getFont().deriveFont(Font.BOLD));
        panel.add(headerCred, gbc);

        gbc.gridx = 1;
        gbc.weightx = WEIGHT_VALUE_COLUMN;
        JLabel headerUser = new JLabel("Username");
        headerUser.setFont(headerUser.getFont().deriveFont(Font.BOLD));
        panel.add(headerUser, gbc);

        gbc.gridx = 2;
        gbc.weightx = WEIGHT_VALUE_COLUMN;
        JLabel headerPass = new JLabel("Password");
        headerPass.setFont(headerPass.getFont().deriveFont(Font.BOLD));
        panel.add(headerPass, gbc);
    }

    private static void addSeparator(JPanel panel, GridBagConstraints gbc) {
        gbc.gridy = 1;
        gbc.gridx = 0;
        gbc.gridwidth = 3;
        gbc.weightx = 1.0;
        panel.add(new JSeparator(), gbc);
        gbc.gridwidth = 1;
    }

    private void addDataRows(JPanel panel, GridBagConstraints gbc,
                             JDialog dialog, Consumer<String> valueConsumer) {
        int row = 2;
        for (Map.Entry<String, Credential> entry : cache.entrySet()) {
            String name = entry.getKey();
            Credential cred = entry.getValue();

            gbc.gridy = row;
            gbc.gridx = 0;
            gbc.weightx = WEIGHT_NAME_COLUMN;
            JLabel nameLabel = new JLabel(name);
            nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD));
            panel.add(nameLabel, gbc);

            gbc.gridx = 1;
            gbc.weightx = WEIGHT_VALUE_COLUMN;
            panel.add(createClickableCell(cred.username(),
                    "Click to insert username", dialog, valueConsumer), gbc);

            gbc.gridx = 2;
            gbc.weightx = WEIGHT_VALUE_COLUMN;
            String masked = cred.password().isEmpty()
                    ? ""
                    : maskPassword(cred.password());
            panel.add(createClickableCell(masked, cred.password(),
                    "Click to insert password", dialog, valueConsumer), gbc);

            row++;
        }
    }

    private static JLabel createClickableCell(String displayText,
                                              String toolTip,
                                              JDialog dialog,
                                              Consumer<String> valueConsumer) {
        return createClickableCell(displayText, displayText, toolTip,
                dialog, valueConsumer);
    }

    private static JLabel createClickableCell(String displayText,
                                              String actualValue,
                                              String toolTip,
                                              JDialog dialog,
                                              Consumer<String> valueConsumer) {
        JLabel label = new JLabel(displayText);
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.setToolTipText(toolTip);
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (valueConsumer != null) {
                    valueConsumer.accept(actualValue);
                }
                dialog.dispose();
            }
        });
        return label;
    }

    // ── Parsing ──────────────────────────────────────────────────────────

    /**
     * Parses the output of {@code show_credentials} (first call) to extract
     * the list of available credential names.
     * <p>
     * Expected format:
     * <pre>
     * Usage: /usr/bin/show_credentials &lt;credential&gt;
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
            if (!inCredentialsSection && trimmed.toLowerCase().startsWith("supported credential")) {
                inCredentialsSection = true;
                continue;
            }
            if (inCredentialsSection) {
                if (trimmed.startsWith("Usage:") || trimmed.startsWith("usage:")) {
                    continue;
                }
                String[] parts = trimmed.split("\\s+");
                if (parts.length > 0 && !parts[0].isEmpty()) {
                    names.add(parts[0]);
                }
            }
        }
        return names;
    }

    /**
     * Parses the output of {@code sudo show_credentials &lt;name&gt;} (second call)
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

    static String maskPassword(String password) {
        if (password == null || password.isEmpty()) {
            return "";
        }
        return "\u2022".repeat(Math.min(password.length(), MAX_MASK_LENGTH));
    }
}
