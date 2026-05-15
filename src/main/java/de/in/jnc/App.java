package de.in.jnc;

import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;

import de.in.jnc.connection.ConnectionFrame;
import de.in.jnc.terminal.SshConnection;
import de.in.jnc.terminal.TerminalSettings;
import de.in.jnc.utils.Log4jTools;

/**
 * Main entry point for the application.
 */
public class App {

	static {
		setupLogPath();
	}

	private static final Logger LOGGER = LogManager.getLogger(App.class);

	public static void main(String[] args) {
		LOGGER.info("Starting jNodeCommander...");

		Log4jTools.redirectStdOutErrLog();
		Log4jTools.logEnvironment(LOGGER);

		// Let FlatLaf handle font and scaling automatically
		FlatLaf.setPreferredFontFamily("Segoe UI");
		FlatDarkLaf.setup();

		Font defaultFont = UIManager.getFont("defaultFont");
		if (defaultFont != null) {
			UIManager.put("defaultFont", defaultFont.deriveFont(defaultFont.getSize2D() + 2f));
		}

		TrayManager.init();

		// Parse CLI arguments for auto-connect / open profiles
		parseCliArgs(args);

		LOGGER.info("Application initialized and running in background.");
	}

	// ─── CLI argument parsing ───────────────────────────────────────────────

	/**
	 * Parses command-line arguments and initiates connections accordingly.
	 * <p>
	 * Supported flags:
	 * <ul>
	 *   <li>{@code --open="name1,name2"} or {@code -o="name1"} — opens saved profiles by name</li>
	 *   <li>{@code --connect="user:password@host:port"} or {@code -c="..."} — ad-hoc connection
	 *       with plain-text credentials. Port is optional, defaults to 22.</li>
	 * </ul>
	 *
	 * @param args the command-line arguments
	 */
	private static void parseCliArgs(String[] args) {
		for (String arg : args) {
			if (arg.startsWith("--open=")) {
				parseOpenProfiles(arg.substring("--open=".length()));
			} else if (arg.startsWith("-o=")) {
				parseOpenProfiles(arg.substring("-o=".length()));
			} else if (arg.startsWith("--connect=")) {
				parseAdhocConnections(arg.substring("--connect=".length()));
			} else if (arg.startsWith("-c=")) {
				parseAdhocConnections(arg.substring("-c=".length()));
			}
		}
	}

	/**
	 * Parses a comma-separated list of saved profile names and opens them.
	 *
	 * @param value the comma-separated profile names
	 */
	private static void parseOpenProfiles(String value) {
		if (value == null || value.isEmpty()) {
			return;
		}
		String[] profileNames = value.split(",");
		for (String name : profileNames) {
			String trimmed = name.trim();
			if (!trimmed.isEmpty()) {
				LOGGER.info("CLI --open requested for profile: '{}'", trimmed);
				openSavedProfile(trimmed);
			}
		}
	}

	/**
	 * Parses a comma-separated list of ad-hoc connection strings and connects.
	 * <p>
	 * Format per entry: {@code <user>:<password>@<hostname>:<port>}
	 * The port is optional and defaults to 22.
	 *
	 * @param value the comma-separated connection strings
	 */
	private static void parseAdhocConnections(String value) {
		if (value == null || value.isEmpty()) {
			return;
		}
		String[] entries = value.split(",");
		for (String entry : entries) {
			String trimmed = entry.trim();
			if (!trimmed.isEmpty()) {
				LOGGER.info("CLI --connect requested: {}", trimmed);
				connectAdhoc(trimmed);
			}
		}
	}

	// ─── Open saved profile ─────────────────────────────────────────────────

	/**
	 * Opens a connection using a saved profile looked up by name.
	 *
	 * @param profileName the name of the profile to connect with
	 */
	private static void openSavedProfile(String profileName) {
		ProfileManager pm = ProfileManager.getInstance();
		ConnectionProfile profile = pm.getProfiles().stream()
				.filter(p -> profileName.equals(p.getName()))
				.findFirst()
				.orElse(null);

		if (profile == null) {
			LOGGER.warn("CLI --open: no profile found with name '{}'", profileName);
			SwingUtilities.invokeLater(() ->
				JOptionPane.showMessageDialog(null,
						"Profile \"" + profileName + "\" not found.",
						"Connection Error",
						JOptionPane.ERROR_MESSAGE));
			return;
		}

		final TerminalSettings termSettings = profile.resolveTerminalSettings();

		// Decrypt password if stored encrypted
		final String decryptedPassword;
		if (profile.getEncryptedPassword() != null && !profile.getEncryptedPassword().isEmpty()) {
			decryptedPassword = CryptoUtil.decrypt(profile.getEncryptedPassword());
		} else {
			decryptedPassword = null;
		}

		SwingWorker<ConnectionFrame, Void> worker = new SwingWorker<>() {
			@Override
			protected ConnectionFrame doInBackground() throws Exception {
				SshConnection sshConnection = new SshConnection(
						profile.getHost(),
						profile.getPort(),
						profile.getUser(),
						decryptedPassword,
						profile.getKeyFilePath());

				sshConnection.connect();
				LOGGER.info("CLI --open: SSH connection established for profile '{}'", profileName);

				profile.setLastUsed(System.currentTimeMillis());
				pm.addOrUpdateProfile(profile);

				return new ConnectionFrame(
						profile.getUser() + "@" + profile.getHost(),
						sshConnection, termSettings, profile);
			}

			@Override
			protected void done() {
				try {
					ConnectionFrame frame = get();
					SwingUtilities.invokeLater(() -> {
						frame.startTerminal();
						frame.setVisible(true);
						LOGGER.info("CLI --open: ConnectionFrame opened for profile '{}'", profileName);
					});
				} catch (Exception e) {
					LOGGER.error("CLI --open: failed to connect profile '{}': {}", profileName, e.getMessage());
					SwingUtilities.invokeLater(() ->
						JOptionPane.showMessageDialog(null,
								"Connection failed for \"" + profileName + "\":\n" + e.getMessage(),
								"Connection Error",
								JOptionPane.ERROR_MESSAGE));
				}
			}
		};
		worker.execute();
	}

	// ─── Ad-hoc connection ──────────────────────────────────────────────────

	/**
	 * Creates a temporary connection from an ad-hoc connection string.
	 * <p>
	 * Expected format: {@code <user>:<password>@<hostname>:<port>}
	 * The port segment (including the colon) is optional and defaults to 22.
	 *
	 * @param spec the connection specification string
	 */
	private static void connectAdhoc(String spec) {
		// Parse: user:password@hostname:port
		int atIndex = spec.indexOf('@');
		if (atIndex < 0) {
			LOGGER.warn("CLI --connect: invalid format '{}' — expected user:password@host:port", spec);
			showError("Invalid connection format: \"" + spec
					+ "\"\nExpected: <user>:<password>@<hostname>:<port>");
			return;
		}

		String userPart = spec.substring(0, atIndex);
		String hostPart = spec.substring(atIndex + 1);

		// user:password
		int colonIndex = userPart.indexOf(':');
		if (colonIndex < 0) {
			LOGGER.warn("CLI --connect: missing user:password separator in '{}'", spec);
			showError("Invalid connection format: missing user:password separator in \"" + spec + "\"");
			return;
		}
		String user = userPart.substring(0, colonIndex);
		String password = userPart.substring(colonIndex + 1);

		// hostname:port (port optional, default 22)
		int lastColon = hostPart.lastIndexOf(':');
		String host;
		int port;
		if (lastColon >= 0) {
			host = hostPart.substring(0, lastColon);
			try {
				port = Integer.parseInt(hostPart.substring(lastColon + 1));
			} catch (NumberFormatException e) {
				LOGGER.warn("CLI --connect: invalid port in '{}'", spec);
				showError("Invalid port number in \"" + spec + "\"");
				return;
			}
		} else {
			host = hostPart;
			port = 22;
		}

		if (user.isEmpty() || host.isEmpty()) {
			LOGGER.warn("CLI --connect: empty user or host in '{}'", spec);
			showError("User and host must not be empty in \"" + spec + "\"");
			return;
		}

		LOGGER.info("CLI --connect: ad-hoc connection to {}@{}:{}", user, host, port);

		final String fqUser = user;
		final String fqPassword = password;
		final String fqHost = host;
		final int fqPort = port;
		final TerminalSettings termSettings = GlobalSettings.getInstance().getTerminalSettings();

		SwingWorker<ConnectionFrame, Void> worker = new SwingWorker<>() {
			@Override
			protected ConnectionFrame doInBackground() throws Exception {
				SshConnection sshConnection = new SshConnection(
						fqHost, fqPort, fqUser,
						fqPassword.isEmpty() ? null : fqPassword,
						null /* no key file */);

				sshConnection.connect();
				LOGGER.info("CLI --connect: ad-hoc SSH connection established to {}@{}:{}", fqUser, fqHost, fqPort);

				// Create a temporary, non-persisted profile for window state tracking
				ConnectionProfile tempProfile = new ConnectionProfile();
				tempProfile.setName(fqUser + "@" + fqHost);
				tempProfile.setHost(fqHost);
				tempProfile.setPort(fqPort);
				tempProfile.setUser(fqUser);
				tempProfile.setLastUsed(System.currentTimeMillis());

				return new ConnectionFrame(
						fqUser + "@" + fqHost,
						sshConnection, termSettings, tempProfile);
			}

			@Override
			protected void done() {
				try {
					ConnectionFrame frame = get();
					SwingUtilities.invokeLater(() -> {
						frame.startTerminal();
						frame.setVisible(true);
						LOGGER.info("CLI --connect: ConnectionFrame opened for {}@{}", fqUser, fqHost);
					});
				} catch (Exception e) {
					LOGGER.error("CLI --connect: failed ad-hoc connection to {}@{}: {}", fqUser, fqHost, e.getMessage());
					SwingUtilities.invokeLater(() ->
						JOptionPane.showMessageDialog(null,
								"Connection failed for " + fqUser + "@" + fqHost + ":\n" + e.getMessage(),
								"Connection Error",
								JOptionPane.ERROR_MESSAGE));
				}
			}
		};
		worker.execute();
	}

	/**
	 * Shows a simple error dialog on the EDT.
	 */
	private static void showError(String message) {
		SwingUtilities.invokeLater(() ->
			JOptionPane.showMessageDialog(null, message, "Connection Error", JOptionPane.ERROR_MESSAGE));
	}

	// ─── Log path setup ─────────────────────────────────────────────────────

	private static void setupLogPath() {
		String logDir = "log";
		Path logPath = Paths.get(logDir);
		boolean writable = false;

		try {
			if (!Files.exists(logPath)) {
				Files.createDirectories(logPath);
			}
			Path testFile = logPath.resolve(".write-test");
			Files.createFile(testFile);
			Files.delete(testFile);
			writable = true;
		} catch (Exception e) {
			// ignore, fallback follows
		}

		if (!writable) {
			String localAppData = System.getenv("LOCALAPPDATA");
			if (localAppData != null) {
				logDir = localAppData + File.separator + "jNodeCommander";
			} else {
				logDir = System.getProperty("user.home") + File.separator + ".jNodeCommander";
			}
			try {
				Files.createDirectories(Paths.get(logDir));
			} catch (IOException e) {
				logDir = ".";
			}
		}
		System.setProperty("jnc.logDir", logDir);
	}
}
