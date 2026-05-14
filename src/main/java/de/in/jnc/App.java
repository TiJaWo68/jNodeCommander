package de.in.jnc;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.awt.Font;
import javax.swing.UIManager;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;

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
		
		LOGGER.info("Application initialized and running in background.");
	}

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