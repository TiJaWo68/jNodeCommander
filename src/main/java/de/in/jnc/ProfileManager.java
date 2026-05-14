package de.in.jnc;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.prefs.Preferences;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Manages saving and loading of connection profiles to/from JSON.
 */
public class ProfileManager {

	private static final Logger LOGGER = LogManager.getLogger(ProfileManager.class);
	private static File PROFILES_FILE = new File(AppEnv.getDataDir(), "profiles.json");
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final List<ConnectionProfile> profiles = new ArrayList<>();

	public enum SortMode { MANUAL, LAST_USED, ALPHABETICAL }
	private SortMode sortMode = SortMode.MANUAL;

	private static ProfileManager instance;

	// For testing
	static void setProfilesFile(File f) {
		PROFILES_FILE = f;
	}

	private ProfileManager() {
		loadProfiles();
		String prefMode = Preferences.userNodeForPackage(ProfileManager.class).get("sortMode", "MANUAL");
		try {
			this.sortMode = SortMode.valueOf(prefMode);
		} catch (Exception e) {
			this.sortMode = SortMode.MANUAL;
		}
	}

	public static synchronized ProfileManager getInstance() {
		if (instance == null) {
			instance = new ProfileManager();
		}
		return instance;
	}

	public SortMode getSortMode() {
		return sortMode;
	}

	public void setSortMode(SortMode sortMode) {
		this.sortMode = sortMode;
		Preferences.userNodeForPackage(ProfileManager.class).put("sortMode", sortMode.name());
	}

	public List<ConnectionProfile> getProfiles() {
		if (sortMode == SortMode.MANUAL) {
			return Collections.unmodifiableList(profiles);
		}
		List<ConnectionProfile> sorted = new ArrayList<>(profiles);
		if (sortMode == SortMode.ALPHABETICAL) {
			sorted.sort(Comparator.comparing(p -> p.getHost() == null ? "" : p.getHost().toLowerCase()));
		} else if (sortMode == SortMode.LAST_USED) {
			sorted.sort((p1, p2) -> Long.compare(p2.getLastUsed(), p1.getLastUsed()));
		}
		return sorted;
	}

	public void addOrUpdateProfile(ConnectionProfile profile) {
		boolean found = false;
		for (int i = 0; i < profiles.size(); i++) {
			if (profiles.get(i).getId().equals(profile.getId())) {
				profiles.set(i, profile);
				found = true;
				break;
			}
		}
		if (!found) {
			profiles.add(profile);
		}
		saveProfiles();
	}

	public void deleteProfile(String id) {
		boolean removed = profiles.removeIf(p -> p.getId().equals(id));
		if (removed) {
			saveProfiles();
		}
	}

	public void moveProfile(int fromIndex, int toIndex) {
		if (fromIndex < 0 || fromIndex >= profiles.size() || toIndex < 0 || toIndex >= profiles.size()) {
			return;
		}
		ConnectionProfile p = profiles.remove(fromIndex);
		profiles.add(toIndex, p);
		saveProfiles();
	}

	// Make package-private for testing
	void loadProfiles() {
		if (!PROFILES_FILE.exists()) {
			return;
		}
		try {
			List<ConnectionProfile> loaded = MAPPER.readValue(PROFILES_FILE, new TypeReference<List<ConnectionProfile>>() {});
			profiles.clear();
			profiles.addAll(loaded);
			LOGGER.info("Loaded {} profiles from {}", profiles.size(), PROFILES_FILE.getAbsolutePath());
		} catch (IOException e) {
			LOGGER.error("Failed to load profiles from " + PROFILES_FILE.getAbsolutePath(), e);
		}
	}

	private void saveProfiles() {
		try {
			MAPPER.writerWithDefaultPrettyPrinter().writeValue(PROFILES_FILE, profiles);
			LOGGER.info("Saved {} profiles to {}", profiles.size(), PROFILES_FILE.getAbsolutePath());
		} catch (IOException e) {
			LOGGER.error("Failed to save profiles to " + PROFILES_FILE.getAbsolutePath(), e);
		}
	}
}
