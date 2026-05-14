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
