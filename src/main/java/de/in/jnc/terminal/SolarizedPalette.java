package de.in.jnc.terminal;

import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;

/**
 * Color constants for the
 * <a href="https://ethanschoonover.com/solarized/">Solarized Dark</a> palette.
 * <p>
 * Extracted from the original {@link SolarizedDarkSettingsProvider} so they can
 * be reused by {@link DynamicSettingsProvider} and future palette implementations.
 */
public final class SolarizedPalette {

    private SolarizedPalette() {
        // prevent instantiation
    }

    // Solarized Dark base colors
    public static final TerminalColor BASE03  = TerminalColor.rgb(0x00, 0x2B, 0x36); // background
    public static final TerminalColor BASE02  = TerminalColor.rgb(0x07, 0x36, 0x42); // selection bg
    public static final TerminalColor BASE01  = TerminalColor.rgb(0x58, 0x6E, 0x75); // emphasized
    public static final TerminalColor BASE00  = TerminalColor.rgb(0x65, 0x7B, 0x83); // body (alt)
    public static final TerminalColor BASE0   = TerminalColor.rgb(0x83, 0x94, 0x96); // body text
    public static final TerminalColor BASE1   = TerminalColor.rgb(0x93, 0xA1, 0xA1); // optional
    public static final TerminalColor BASE2   = TerminalColor.rgb(0xEE, 0xE8, 0xD5); // light bg
    public static final TerminalColor BASE3   = TerminalColor.rgb(0xFD, 0xF6, 0xE3); // lightest

    // Solarized Dark ANSI colors
    public static final TerminalColor YELLOW  = TerminalColor.rgb(0xB5, 0x89, 0x00);
    public static final TerminalColor ORANGE  = TerminalColor.rgb(0xCB, 0x4B, 0x16);
    public static final TerminalColor RED     = TerminalColor.rgb(0xDC, 0x32, 0x2F);
    public static final TerminalColor MAGENTA = TerminalColor.rgb(0xD3, 0x36, 0x82);
    public static final TerminalColor VIOLET  = TerminalColor.rgb(0x6C, 0x71, 0xC4);
    public static final TerminalColor BLUE    = TerminalColor.rgb(0x26, 0x8B, 0xD2);
    public static final TerminalColor CYAN    = TerminalColor.rgb(0x2A, 0xA1, 0x98);
    public static final TerminalColor GREEN   = TerminalColor.rgb(0x85, 0x99, 0x00);

    // Shortcuts for the most commonly used colors
    public static final TerminalColor DEFAULT_FOREGROUND = BASE0;
    public static final TerminalColor DEFAULT_BACKGROUND = BASE03;
    public static final TerminalColor SELECTION_BACKGROUND = BASE02;

    /**
     * Returns the selection color style (foreground on selection background).
     *
     * @return selection TextStyle
     */
    public static TextStyle selectionColor() {
        return new TextStyle(DEFAULT_FOREGROUND, SELECTION_BACKGROUND);
    }

    /**
     * Returns the found pattern color style (dark background on yellow foreground).
     *
     * @return found pattern TextStyle
     */
    public static TextStyle foundPatternColor() {
        return new TextStyle(BASE03, YELLOW);
    }

    /**
     * Returns the hyperlink color style (blue on dark background).
     *
     * @return hyperlink TextStyle
     */
    public static TextStyle hyperlinkColor() {
        return new TextStyle(BLUE, BASE03);
    }
}
