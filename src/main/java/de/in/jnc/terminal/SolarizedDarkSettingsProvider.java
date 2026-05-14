package de.in.jnc.terminal;

import java.awt.Font;

import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;

/**
 * A JediTerm {@link com.jediterm.terminal.ui.settings.SettingsProvider} using the
 * <a href="https://ethanschoonover.com/solarized/">Solarized Dark</a> color scheme.
 * <p>
 * Background is dark blue-grey ({@code #002B36}), foreground is light grey
 * ({@code #839496}), with a distinct color palette for ANSI terminal colors.
 */
public class SolarizedDarkSettingsProvider extends DefaultSettingsProvider {

    // Solarized Dark palette constants
    private static final TerminalColor BASE03  = TerminalColor.rgb(0x00, 0x2B, 0x36); // background
    private static final TerminalColor BASE02  = TerminalColor.rgb(0x07, 0x36, 0x42); // selection bg
    private static final TerminalColor BASE01  = TerminalColor.rgb(0x58, 0x6E, 0x75); // emphasized
    private static final TerminalColor BASE00  = TerminalColor.rgb(0x65, 0x7B, 0x83); // body (alt)
    private static final TerminalColor BASE0   = TerminalColor.rgb(0x83, 0x94, 0x96); // body text
    private static final TerminalColor BASE1   = TerminalColor.rgb(0x93, 0xA1, 0xA1); // optional
    private static final TerminalColor BASE2   = TerminalColor.rgb(0xEE, 0xE8, 0xD5); // light bg
    private static final TerminalColor BASE3   = TerminalColor.rgb(0xFD, 0xF6, 0xE3); // lightest

    private static final TerminalColor YELLOW  = TerminalColor.rgb(0xB5, 0x89, 0x00);
    private static final TerminalColor ORANGE  = TerminalColor.rgb(0xCB, 0x4B, 0x16);
    private static final TerminalColor RED     = TerminalColor.rgb(0xDC, 0x32, 0x2F);
    private static final TerminalColor MAGENTA = TerminalColor.rgb(0xD3, 0x36, 0x82);
    private static final TerminalColor VIOLET  = TerminalColor.rgb(0x6C, 0x71, 0xC4);
    private static final TerminalColor BLUE    = TerminalColor.rgb(0x26, 0x8B, 0xD2);
    private static final TerminalColor CYAN    = TerminalColor.rgb(0x2A, 0xA1, 0x98);
    private static final TerminalColor GREEN   = TerminalColor.rgb(0x85, 0x99, 0x00);

    @Override
    public TerminalColor getDefaultForeground() {
        return BASE0;
    }

    @Override
    public TerminalColor getDefaultBackground() {
        return BASE03;
    }

    @Override
    public TextStyle getSelectionColor() {
        return new TextStyle(BASE0, BASE02);
    }

    @Override
    public TextStyle getFoundPatternColor() {
        return new TextStyle(BASE03, YELLOW);
    }

    @Override
    public TextStyle getHyperlinkColor() {
        return new TextStyle(BLUE, BASE03);
    }

    @Override
    public Font getTerminalFont() {
        String fontName;
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            fontName = "Consolas";
        } else if (os.contains("mac")) {
            fontName = "Menlo";
        } else {
            fontName = "Monospaced";
        }
        return new Font(fontName, Font.PLAIN, (int) getTerminalFontSize());
    }

    @Override
    public boolean useInverseSelectionColor() {
        return false;
    }
}
