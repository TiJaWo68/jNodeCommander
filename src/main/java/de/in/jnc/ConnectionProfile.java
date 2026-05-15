package de.in.jnc;

import java.util.Objects;
import java.util.UUID;

import de.in.jnc.terminal.TerminalSettings;

/**
 * Represents a saved SSH connection profile.
 */
public class ConnectionProfile {

	private String id;
	private String name;
	private String host;
	private int port;
	private String user;
	private String encryptedPassword;
	private String keyFilePath;
	private long lastUsed;

	/** Last local directory used in the file transfer panel, or null. */
	private String lastLocalDirectory;

	/** Last remote directory used in the file transfer panel, or null. */
	private String lastRemoteDirectory;

	/** Last window X position, or -1 if not set. */
	private int windowX = -1;

	/** Last window Y position, or -1 if not set. */
	private int windowY = -1;

	/** Last window width, or -1 if not set. */
	private int windowWidth = -1;

	/** Last window height, or -1 if not set. */
	private int windowHeight = -1;

	/**
	 * Optional per-profile terminal settings override.
	 * If null, the global terminal settings from {@link GlobalSettings} are used.
	 */
	private TerminalSettings terminalSettingsOverride;

	public ConnectionProfile() {
		// Required for Jackson deserialization
		this.id = UUID.randomUUID().toString();
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getHost() {
		return host;
	}

	public void setHost(String host) {
		this.host = host;
	}

	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public String getUser() {
		return user;
	}

	public void setUser(String user) {
		this.user = user;
	}

	public String getEncryptedPassword() {
		return encryptedPassword;
	}

	public void setEncryptedPassword(String encryptedPassword) {
		this.encryptedPassword = encryptedPassword;
	}

	public String getKeyFilePath() {
		return keyFilePath;
	}

	public void setKeyFilePath(String keyFilePath) {
		this.keyFilePath = keyFilePath;
	}

	public long getLastUsed() {
		return lastUsed;
	}

	public void setLastUsed(long lastUsed) {
		this.lastUsed = lastUsed;
	}

	/**
	 * Returns the per-profile terminal settings override, if set.
	 *
	 * @return the terminal settings override, or null
	 */
	public TerminalSettings getTerminalSettingsOverride() {
		return terminalSettingsOverride;
	}

	/**
	 * Sets a per-profile override for terminal settings.
	 *
	 * @param terminalSettingsOverride the settings to use for this profile, or null to use global defaults
	 */
	public void setTerminalSettingsOverride(TerminalSettings terminalSettingsOverride) {
		this.terminalSettingsOverride = terminalSettingsOverride;
	}

	/**
	 * Resolves the effective terminal settings for this profile.
	 * <p>
	 * If a per-profile override exists, it is returned.
	 * Otherwise, the global default settings from {@link GlobalSettings} are used.
	 *
	 * @return the effective TerminalSettings to use when connecting
	 */
	/**
	 * Returns the last local directory used in the file transfer panel.
	 *
	 * @return the last local directory path, or null
	 */
	public String getLastLocalDirectory() {
		return lastLocalDirectory;
	}

	/**
	 * Sets the last local directory used in the file transfer panel.
	 *
	 * @param lastLocalDirectory the directory path to persist
	 */
	public void setLastLocalDirectory(String lastLocalDirectory) {
		this.lastLocalDirectory = lastLocalDirectory;
	}

	/**
	 * Returns the last remote directory used in the file transfer panel.
	 *
	 * @return the last remote directory path, or null
	 */
	public String getLastRemoteDirectory() {
		return lastRemoteDirectory;
	}

	/**
	 * Sets the last remote directory used in the file transfer panel.
	 *
	 * @param lastRemoteDirectory the directory path to persist
	 */
	public void setLastRemoteDirectory(String lastRemoteDirectory) {
		this.lastRemoteDirectory = lastRemoteDirectory;
	}

	/**
	 * Returns the last window X position, or -1 if not set.
	 *
	 * @return the X coordinate, or -1
	 */
	public int getWindowX() {
		return windowX;
	}

	/**
	 * Sets the last window X position.
	 *
	 * @param windowX the X coordinate
	 */
	public void setWindowX(int windowX) {
		this.windowX = windowX;
	}

	/**
	 * Returns the last window Y position, or -1 if not set.
	 *
	 * @return the Y coordinate, or -1
	 */
	public int getWindowY() {
		return windowY;
	}

	/**
	 * Sets the last window Y position.
	 *
	 * @param windowY the Y coordinate
	 */
	public void setWindowY(int windowY) {
		this.windowY = windowY;
	}

	/**
	 * Returns the last window width, or -1 if not set.
	 *
	 * @return the width in pixels, or -1
	 */
	public int getWindowWidth() {
		return windowWidth;
	}

	/**
	 * Sets the last window width.
	 *
	 * @param windowWidth the width in pixels
	 */
	public void setWindowWidth(int windowWidth) {
		this.windowWidth = windowWidth;
	}

	/**
	 * Returns the last window height, or -1 if not set.
	 *
	 * @return the height in pixels, or -1
	 */
	public int getWindowHeight() {
		return windowHeight;
	}

	/**
	 * Sets the last window height.
	 *
	 * @param windowHeight the height in pixels
	 */
	public void setWindowHeight(int windowHeight) {
		this.windowHeight = windowHeight;
	}

	public TerminalSettings resolveTerminalSettings() {
		if (terminalSettingsOverride != null) {
			return terminalSettingsOverride;
		}
		return GlobalSettings.getInstance().getTerminalSettings();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		ConnectionProfile profile = (ConnectionProfile) o;
		return id.equals(profile.id);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id);
	}

	@Override
	public String toString() {
		return name; // Useful for displaying in a JList
	}
}
