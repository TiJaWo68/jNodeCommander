package de.in.jnc;

import java.util.Objects;
import java.util.UUID;

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
