package de.in.jnc;

import java.io.File;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility for resolving environment-specific paths and properties.
 */
public class AppEnv {

	private static final Logger LOGGER = LogManager.getLogger(AppEnv.class);

	/**
	 * Returns the data directory for jNodeCommander.
	 * Windows: %LOCALAPPDATA%/jNodeCommander
	 * Mac: ~/Library/Application Support/jNodeCommander
	 * Linux/Other: ~/.jNodeCommander
	 */
	public static File getDataDir() {
		String os = System.getProperty("os.name").toLowerCase();
		File dataDir;
		if (os.contains("win")) {
			String localAppData = System.getenv("LOCALAPPDATA");
			if (localAppData == null || localAppData.isBlank()) {
				// Fallback if env var is missing
				localAppData = System.getProperty("user.home") + File.separator + "AppData" + File.separator + "Local";
			}
			dataDir = new File(localAppData, "jNodeCommander");
		} else if (os.contains("mac")) {
			dataDir = new File(System.getProperty("user.home"), "Library/Application Support/jNodeCommander");
		} else {
			dataDir = new File(System.getProperty("user.home"), ".jNodeCommander");
		}

		if (!dataDir.exists()) {
			boolean created = dataDir.mkdirs();
			if (created) {
				LOGGER.info("Created application data directory at: {}", dataDir.getAbsolutePath());
			} else {
				LOGGER.error("Failed to create application data directory at: {}", dataDir.getAbsolutePath());
			}
		}

		return dataDir;
	}

	/**
	 * Returns true if the application was started with -Djnc.savePasswords=true
	 */
	public static boolean isSavePasswordsEnabled() {
		return "true".equalsIgnoreCase(System.getProperty("jnc.savePasswords", "false"));
	}
}
