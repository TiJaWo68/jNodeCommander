package de.in.jnc.terminal;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.jediterm.terminal.CursorShape;

/**
 * Configuration data for terminal appearance and behavior.
 * <p>
 * This POJO is serialized to JSON (via Jackson) as part of
 * {@link de.in.jnc.GlobalSettings} or as a per-profile override in
 * {@link de.in.jnc.ConnectionProfile}.
 * </p>
 * <p>
 * String-based enum constants are used instead of Java enums for robust JSON
 * deserialization – renaming an enum would break existing settings files.
 * </p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TerminalSettings {

    // Color scheme constants
    public static final String SCHEME_SOLARIZED_DARK = "SOLARIZED_DARK";
    /** White text on black background (classic terminal look). */
    public static final String SCHEME_WHITE_ON_BLACK = "WHITE_ON_BLACK";
    /** Black text on white background (inverted terminal look). */
    public static final String SCHEME_BLACK_ON_WHITE = "BLACK_ON_WHITE";
    public static final String SCHEME_CUSTOM = "CUSTOM";

    /** @deprecated Use {@link #SCHEME_BLACK_ON_WHITE} instead. Kept for JSON backward compatibility. */
    @Deprecated
    public static final String SCHEME_DEFAULT = "DEFAULT";

    // Cursor shape constants (matching CursorShape enum names)
    public static final String CURSOR_BLINK_BLOCK = "BLINK_BLOCK";
    public static final String CURSOR_STEADY_BLOCK = "STEADY_BLOCK";
    public static final String CURSOR_BLINK_UNDERLINE = "BLINK_UNDERLINE";
    public static final String CURSOR_STEADY_UNDERLINE = "STEADY_UNDERLINE";
    public static final String CURSOR_BLINK_VERTICAL_BAR = "BLINK_VERTICAL_BAR";
    public static final String CURSOR_STEADY_VERTICAL_BAR = "STEADY_VERTICAL_BAR";

    private String colorScheme = SCHEME_SOLARIZED_DARK;

    /** Font family name, empty string means "use OS default". */
    private String fontFamily = "";

    /** Font size in points. */
    private int fontSize = 14;

    /** Cursor shape as a string matching {@link CursorShape} enum names. */
    private String cursorShape = CURSOR_BLINK_BLOCK;

    /** Cursor blink rate in milliseconds (0 = no blinking). */
    private int cursorBlinkRateMs = 505;

    /** Foreground color as hex string (e.g. "#839496"), only used when colorScheme is CUSTOM. */
    private String customForeground;

    /** Background color as hex string (e.g. "#002B36"), only used when colorScheme is CUSTOM. */
    private String customBackground;

    /**
     * Creates a new TerminalSettings with default (Solarized Dark) values.
     */
    public TerminalSettings() {
        // Default constructor for Jackson deserialization
    }

    // --- Factory methods ---

    /**
     * Returns a default TerminalSettings with Solarized Dark color scheme.
     *
     * @return a new instance with Solarized Dark defaults
     */
    public static TerminalSettings createDefault() {
        return createSolarizedDark();
    }

    /**
     * Returns a TerminalSettings pre-configured for Solarized Dark.
     *
     * @return a new Solarized Dark instance
     */
    public static TerminalSettings createSolarizedDark() {
        TerminalSettings settings = new TerminalSettings();
        settings.colorScheme = SCHEME_SOLARIZED_DARK;
        settings.fontFamily = "";
        settings.fontSize = 14;
        settings.cursorShape = CURSOR_BLINK_BLOCK;
        settings.cursorBlinkRateMs = 505;
        return settings;
    }

    // --- Helper methods ---

    /**
     * Converts the string-based cursorShape to a JediTerm {@link CursorShape}.
     *
     * @return the corresponding CursorShape, never null
     * @throws IllegalArgumentException if the string does not match any known shape
     */
    @JsonIgnore
    public CursorShape getEffectiveCursorShape() {
        return CursorShape.valueOf(cursorShape);
    }

    /**
     * Returns whether the selected cursor shape is blinking.
     *
     * @return true if the cursor blinks
     */
    @JsonIgnore
    public boolean isCursorBlinking() {
        return getEffectiveCursorShape().isBlinking();
    }

    // --- Getters and setters ---

    public String getColorScheme() {
        return colorScheme;
    }

    public void setColorScheme(String colorScheme) {
        this.colorScheme = colorScheme;
    }

    public String getFontFamily() {
        return fontFamily;
    }

    public void setFontFamily(String fontFamily) {
        this.fontFamily = fontFamily;
    }

    public int getFontSize() {
        return fontSize;
    }

    public void setFontSize(int fontSize) {
        this.fontSize = fontSize;
    }

    public String getCursorShape() {
        return cursorShape;
    }

    public void setCursorShape(String cursorShape) {
        this.cursorShape = cursorShape;
    }

    public int getCursorBlinkRateMs() {
        return cursorBlinkRateMs;
    }

    public void setCursorBlinkRateMs(int cursorBlinkRateMs) {
        this.cursorBlinkRateMs = cursorBlinkRateMs;
    }

    public String getCustomForeground() {
        return customForeground;
    }

    public void setCustomForeground(String customForeground) {
        this.customForeground = customForeground;
    }

    public String getCustomBackground() {
        return customBackground;
    }

    public void setCustomBackground(String customBackground) {
        this.customBackground = customBackground;
    }

    // --- Object overrides ---

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TerminalSettings that = (TerminalSettings) o;
        return fontSize == that.fontSize
                && cursorBlinkRateMs == that.cursorBlinkRateMs
                && Objects.equals(colorScheme, that.colorScheme)
                && Objects.equals(fontFamily, that.fontFamily)
                && Objects.equals(cursorShape, that.cursorShape)
                && Objects.equals(customForeground, that.customForeground)
                && Objects.equals(customBackground, that.customBackground);
    }

    @Override
    public int hashCode() {
        return Objects.hash(colorScheme, fontFamily, fontSize, cursorShape,
                cursorBlinkRateMs, customForeground, customBackground);
    }

    @Override
    public String toString() {
        return "TerminalSettings{"
                + "colorScheme='" + colorScheme + '\''
                + ", fontFamily='" + fontFamily + '\''
                + ", fontSize=" + fontSize
                + ", cursorShape='" + cursorShape + '\''
                + ", cursorBlinkRateMs=" + cursorBlinkRateMs
                + '}';
    }
}
