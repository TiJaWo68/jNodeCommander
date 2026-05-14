package de.in.jnc.terminal;

import java.awt.Font;

import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.ColorPalette;
import com.jediterm.terminal.emulator.ColorPaletteImpl;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;

import static com.jediterm.terminal.ui.UtilKt.isMacOS;
import static com.jediterm.terminal.ui.UtilKt.isWindows;

/**
 * A {@link com.jediterm.terminal.ui.settings.SettingsProvider} that reads all
 * terminal appearance values from a {@link TerminalSettings} instance.
 * <p>
 * This replaces the hardcoded {@link SolarizedDarkSettingsProvider} and supports
 * multiple color schemes, configurable fonts and cursor behavior.
 */
public class DynamicSettingsProvider extends DefaultSettingsProvider {

    private final TerminalSettings settings;

    /**
     * Creates a new DynamicSettingsProvider with the given settings.
     *
     * @param settings the terminal configuration to apply
     */
    public DynamicSettingsProvider(TerminalSettings settings) {
        this.settings = settings;
    }

    @Override
    public Font getTerminalFont() {
        String fontName = settings.getFontFamily();
        if (fontName == null || fontName.isBlank()) {
            // OS-specific default
            if (isWindows()) {
                fontName = "Consolas";
            } else if (isMacOS()) {
                fontName = "Menlo";
            } else {
                fontName = "Monospaced";
            }
        }
        return new Font(fontName, Font.PLAIN, settings.getFontSize());
    }

    @Override
    public float getTerminalFontSize() {
        return settings.getFontSize();
    }

    @Override
    public TerminalColor getDefaultForeground() {
        return switch (settings.getColorScheme()) {
            case TerminalSettings.SCHEME_SOLARIZED_DARK -> SolarizedPalette.DEFAULT_FOREGROUND;
            case TerminalSettings.SCHEME_WHITE_ON_BLACK -> TerminalColor.WHITE;
            case TerminalSettings.SCHEME_BLACK_ON_WHITE, TerminalSettings.SCHEME_DEFAULT -> TerminalColor.BLACK;
            case TerminalSettings.SCHEME_CUSTOM -> resolveCustomColor(settings.getCustomForeground(), TerminalColor.WHITE);
            default -> TerminalColor.WHITE;
        };
    }

    @Override
    public TerminalColor getDefaultBackground() {
        return switch (settings.getColorScheme()) {
            case TerminalSettings.SCHEME_SOLARIZED_DARK -> SolarizedPalette.DEFAULT_BACKGROUND;
            case TerminalSettings.SCHEME_WHITE_ON_BLACK -> TerminalColor.BLACK;
            case TerminalSettings.SCHEME_BLACK_ON_WHITE, TerminalSettings.SCHEME_DEFAULT -> TerminalColor.WHITE;
            case TerminalSettings.SCHEME_CUSTOM -> resolveCustomColor(settings.getCustomBackground(), TerminalColor.BLACK);
            default -> TerminalColor.BLACK;
        };
    }

    @Override
    public TextStyle getSelectionColor() {
        return switch (settings.getColorScheme()) {
            case TerminalSettings.SCHEME_SOLARIZED_DARK -> SolarizedPalette.selectionColor();
            default -> super.getSelectionColor();
        };
    }

    @Override
    public TextStyle getFoundPatternColor() {
        return switch (settings.getColorScheme()) {
            case TerminalSettings.SCHEME_SOLARIZED_DARK -> SolarizedPalette.foundPatternColor();
            default -> super.getFoundPatternColor();
        };
    }

    @Override
    public TextStyle getHyperlinkColor() {
        return switch (settings.getColorScheme()) {
            case TerminalSettings.SCHEME_SOLARIZED_DARK -> SolarizedPalette.hyperlinkColor();
            default -> super.getHyperlinkColor();
        };
    }

    @Override
    public ColorPalette getTerminalColorPalette() {
        return switch (settings.getColorScheme()) {
            case TerminalSettings.SCHEME_SOLARIZED_DARK -> SolarizedColorPalette.INSTANCE;
            default -> super.getTerminalColorPalette();
        };
    }

    @Override
    public boolean useInverseSelectionColor() {
        return switch (settings.getColorScheme()) {
            case TerminalSettings.SCHEME_SOLARIZED_DARK -> false;
            default -> super.useInverseSelectionColor();
        };
    }

    @Override
    public int caretBlinkingMs() {
        return settings.getCursorBlinkRateMs();
    }

    /**
     * Resolves a custom hex color or falls back to the given default.
     *
     * @param customHex  hex color string (e.g. "#FF8800")
     * @param fallback   fallback color if custom hex is empty/null
     * @return the resolved TerminalColor
     */
    private TerminalColor resolveCustomColor(String customHex, TerminalColor fallback) {
        if (customHex != null && !customHex.isBlank()) {
            try {
                return parseHexColor(customHex);
            } catch (IllegalArgumentException e) {
                // fall through to fallback
            }
        }
        return fallback;
    }

    /**
     * Parses a hex color string (e.g. "#FF8800" or "FF8800") to a TerminalColor.
     *
     * @param hex the hex color string
     * @return the parsed TerminalColor
     * @throws IllegalArgumentException if the string is not a valid hex color
     */
    private static TerminalColor parseHexColor(String hex) {
        String sanitized = hex.startsWith("#") ? hex.substring(1) : hex;
        if (sanitized.length() != 6) {
            throw new IllegalArgumentException("Invalid hex color: " + hex);
        }
        int rgb = Integer.parseInt(sanitized, 16);
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return TerminalColor.rgb(r, g, b);
    }

    /**
     * A Solarized Dark {@link ColorPalette} providing the 16 ANSI terminal colors
     * according to the Solarized Dark specification.
     */
    private static final class SolarizedColorPalette extends ColorPalette {

        private static final SolarizedColorPalette INSTANCE = new SolarizedColorPalette();

        private static final com.jediterm.core.Color[] SOLARIZED_ANSI_COLORS = {
            toAwtColor(SolarizedPalette.BASE02),   // Black
            toAwtColor(SolarizedPalette.RED),       // Red
            toAwtColor(SolarizedPalette.GREEN),     // Green
            toAwtColor(SolarizedPalette.YELLOW),    // Yellow
            toAwtColor(SolarizedPalette.BLUE),      // Blue
            toAwtColor(SolarizedPalette.MAGENTA),   // Magenta
            toAwtColor(SolarizedPalette.CYAN),      // Cyan
            toAwtColor(SolarizedPalette.BASE2),     // White
            // Bright versions
            toAwtColor(SolarizedPalette.BASE03),    // Bright Black
            toAwtColor(SolarizedPalette.ORANGE),    // Bright Red
            toAwtColor(SolarizedPalette.BASE01),    // Bright Green
            toAwtColor(SolarizedPalette.BASE00),    // Bright Yellow
            toAwtColor(SolarizedPalette.BASE0),     // Bright Blue
            toAwtColor(SolarizedPalette.VIOLET),    // Bright Magenta
            toAwtColor(SolarizedPalette.BASE1),     // Bright Cyan
            toAwtColor(SolarizedPalette.BASE3),     // Bright White
        };

        private static com.jediterm.core.Color toAwtColor(TerminalColor tc) {
            // TerminalColor.toColor() returns com.jediterm.core.Color
            return tc.toColor();
        }

        @Override
        public @org.jetbrains.annotations.NotNull com.jediterm.core.Color getForegroundByColorIndex(int colorIndex) {
            return SOLARIZED_ANSI_COLORS[colorIndex];
        }

        @Override
        protected @org.jetbrains.annotations.NotNull com.jediterm.core.Color getBackgroundByColorIndex(int colorIndex) {
            return SOLARIZED_ANSI_COLORS[colorIndex];
        }
    }
}
