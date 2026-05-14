package de.in.jnc;

import java.awt.AWTException;
import java.awt.Image;
import java.awt.Menu;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;

import javax.swing.SwingUtilities;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.formdev.flatlaf.extras.FlatSVGIcon;

/**
 * Manages the application's presence in the system tray.
 */
public class TrayManager {

	private static final Logger LOGGER = LogManager.getLogger(TrayManager.class);
	private static final String ICON_PATH = "jnc.svg";

	private static ConnectionDialog activeConnectionDialog;
	private static Menu connectionMenu;

	public static void init() {
		if (!SystemTray.isSupported()) {
			LOGGER.error("SystemTray is not supported on this platform.");
			return;
		}

		final SystemTray tray = SystemTray.getSystemTray();

		int iconSize = tray.getTrayIconSize().width;
		FlatSVGIcon svgIcon = new FlatSVGIcon(ICON_PATH, iconSize, iconSize);
		Image image = svgIcon.getImage();

		PopupMenu popup = createPopupMenu();
		TrayIcon trayIcon = new TrayIcon(image, "jNodeCommander", popup);
		trayIcon.setImageAutoSize(true);

		try {
			tray.add(trayIcon);
			LOGGER.info("SystemTray icon initialized successfully using {}.", ICON_PATH);
		} catch (AWTException e) {
			LOGGER.error("Could not add TrayIcon to SystemTray", e);
		}
	}

	private static PopupMenu createPopupMenu() {
		PopupMenu popup = new PopupMenu();

		connectionMenu = new Menu("Connection");
		rebuildConnectionMenu();

		popup.add(connectionMenu);
		popup.addSeparator();

		MenuItem exitItem = new MenuItem("Exit");
		exitItem.addActionListener(e -> {
			LOGGER.info("Exiting application via Tray menu.");
			System.exit(0);
		});
		popup.add(exitItem);

		return popup;
	}

	public static void rebuildConnectionMenu() {
		if (connectionMenu == null) {
			return;
		}
		connectionMenu.removeAll();

		MenuItem newItem = new MenuItem("New...");
		newItem.addActionListener(e -> openConnectionDialog(null));
		connectionMenu.add(newItem);

		List<ConnectionProfile> profiles = ProfileManager.getInstance().getProfiles();
		if (!profiles.isEmpty()) {
			connectionMenu.addSeparator();
			for (ConnectionProfile profile : profiles) {
				MenuItem profileItem = new MenuItem(profile.getName());
				profileItem.addActionListener(e -> openConnectionDialog(profile));
				connectionMenu.add(profileItem);
			}
		}

		connectionMenu.addSeparator();
		MenuItem manageItem = new MenuItem("Manage Profiles...");
		manageItem.addActionListener(e -> {
			SwingUtilities.invokeLater(() -> {
				ProfileManagerDialog dialog = new ProfileManagerDialog(TrayManager::rebuildConnectionMenu);
				dialog.setVisible(true);
			});
		});
		connectionMenu.add(manageItem);
	}

	private static void openConnectionDialog(ConnectionProfile profile) {
		SwingUtilities.invokeLater(() -> {
			if (activeConnectionDialog != null) {
				activeConnectionDialog.dispose();
			}
			if (profile == null) {
				LOGGER.info("User requested 'New Connection' dialog.");
				activeConnectionDialog = new ConnectionDialog();
			} else {
				LOGGER.info("User requested connection for profile: {}", profile.getName());
				activeConnectionDialog = new ConnectionDialog(profile);
			}
			
			activeConnectionDialog.addWindowListener(new WindowAdapter() {
				@Override
				public void windowClosed(WindowEvent e) {
					activeConnectionDialog = null;
					// Rebuild in case a new profile was saved
					rebuildConnectionMenu();
				}
			});
			activeConnectionDialog.setVisible(true);
		});
	}
}
