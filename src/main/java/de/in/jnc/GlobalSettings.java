package de.in.jnc;

import java.io.File;
import java.io.IOException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import de.in.jnc.terminal.TerminalSettings;

/**
 * Singleton for global application settings (independent of connection profiles).
 * <p>
 * Persisted as JSON in {@code %LOCALAPPDATA%/jNodeCommander/settings.json}.
 * Currently contains only {@link TerminalSettings}; will be extended with more
 * global preferences in future Epics.
 * </p>
 */
public class GlobalSettings {

    private static final Logger LOGGER = LogManager.getLogger(GlobalSettings.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final File SETTINGS_FILE = new File(AppEnv.getDataDir(), "settings.json");

    private static GlobalSettings instance;

    private TerminalSettings terminalSettings = TerminalSettings.createDefault();

    private GlobalSettings() {
        // No load() call here – Jackson deserialization would cause infinite recursion.
        // load() is called explicitly from getInstance() after construction.
    }

    /**
     * Returns the singleton instance.
     *
     * @return the GlobalSettings instance
     */
    public static synchronized GlobalSettings getInstance() {
        if (instance == null) {
            instance = new GlobalSettings();
            instance.load(); // Load persisted settings after construction
        }
        return instance;
    }

    // --- For testing ---
    static void resetInstance() {
        instance = null;
    }

    static void setSettingsFile(File file) {
        // Only for testing
    }

    // --- Terminal settings ---

    public TerminalSettings getTerminalSettings() {
        return terminalSettings;
    }

    public void setTerminalSettings(TerminalSettings terminalSettings) {
        this.terminalSettings = terminalSettings;
        save();
    }

    // --- Persistence ---

    /**
     * Loads settings from the JSON file. If the file does not exist or is corrupt,
     * defaults are used.
     */
    public void load() {
        if (!SETTINGS_FILE.exists()) {
            LOGGER.info("No settings file found at {}, using defaults", SETTINGS_FILE.getAbsolutePath());
            return;
        }
        try {
            GlobalSettings loaded = MAPPER.readValue(SETTINGS_FILE, GlobalSettings.class);
            if (loaded != null) {
                if (loaded.terminalSettings != null) {
                    this.terminalSettings = loaded.terminalSettings;
                }
                LOGGER.info("Loaded global settings from {}", SETTINGS_FILE.getAbsolutePath());
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load settings from {}, using defaults: {}",
                    SETTINGS_FILE.getAbsolutePath(), e.getMessage());
        }
    }

    /**
     * Saves the current settings to the JSON file.
     */
    public void save() {
        try {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(SETTINGS_FILE, this);
            LOGGER.info("Saved global settings to {}", SETTINGS_FILE.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.error("Failed to save settings to {}: {}",
                    SETTINGS_FILE.getAbsolutePath(), e.getMessage());
        }
    }

    // --- Jackson needs a no-arg constructor for deserialization ---
    // The private no-arg constructor above is used by Jackson.
    // It does NOT call load() to avoid infinite recursion during deserialization.
}
