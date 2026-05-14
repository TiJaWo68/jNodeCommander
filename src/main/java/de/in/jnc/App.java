package de.in.jnc;

import java.awt.Dimension;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.formdev.flatlaf.FlatDarkLaf;

import de.in.jnc.utils.Log4jTools;

/**
 * Main entry point for the application. Responsible for initializing the UI framework and starting the main window.
 */
public class App {

	static {
		setupLogPath();
	}

	private static final Logger LOGGER = LogManager.getLogger(App.class);
	private static final String APP_TITLE = "jNodeCommander";
	private static final int DEFAULT_WIDTH = 1024;
	private static final int DEFAULT_HEIGHT = 768;

	public static void main(String[] args) {
		LOGGER.info("Starting jNodeCommander...");
		LOGGER.info("Log directory set to: {}", System.getProperty("jnc.logDir"));

		// redirect log and log environment details
		Log4jTools.redirectStdOutErrLog();
		Log4jTools.logEnvironment(LOGGER);

		// Setup FlatLaf for a modern, dark look and feel before initializing any Swing components
		FlatDarkLaf.setup();

		// Ensure UI creation and updates happen exclusively on the Event Dispatch Thread (EDT)
		SwingUtilities.invokeLater(App::createAndShowGUI);
	}

	private static void setupLogPath() {
		String logDir = "log";
		Path logPath = Paths.get(logDir);
		boolean writable = false;

		try {
			if (!Files.exists(logPath)) {
				Files.createDirectories(logPath);
			}
			// Check if we can actually write to the directory
			Path testFile = logPath.resolve(".write-test");
			Files.createFile(testFile);
			Files.delete(testFile);
			writable = true;
		} catch (Exception e) {
			// Local log dir not writable
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
				logDir = "."; // Last resort
			}
		}
		System.setProperty("jnc.logDir", logDir);
	}

	private static void createAndShowGUI() {
		JFrame mainFrame = new JFrame(APP_TITLE);
		mainFrame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
		mainFrame.setPreferredSize(new Dimension(DEFAULT_WIDTH, DEFAULT_HEIGHT));

		// TODO (Temporary): This is just the skeleton. The tray logic and UI wiring will go here.

		mainFrame.pack();
		mainFrame.setLocationRelativeTo(null); // Center the window on the screen
		mainFrame.setVisible(true);
	}
}