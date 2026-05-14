package de.in.jnc.terminal;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

import org.cuberact.swing.layout.Cell;
import org.cuberact.swing.layout.Composite;

/**
 * A reusable Swing panel for editing {@link TerminalSettings}.
 * <p>
 * Can be used in two modes:
 * <ul>
 *   <li><b>Global mode</b> ({@code isPerProfileMode = false}): All fields are always editable.</li>
 *   <li><b>Per-profile mode</b> ({@code isPerProfileMode = true}): Shows a "Use global settings"
 *       checkbox; when checked, all fields are disabled and the global defaults are applied.</li>
 * </ul>
 */
public class TerminalSettingsPanel extends JPanel {

    private static final String[] COLOR_SCHEMES = {
        TerminalSettings.SCHEME_SOLARIZED_DARK,
        TerminalSettings.SCHEME_WHITE_ON_BLACK,
        TerminalSettings.SCHEME_BLACK_ON_WHITE,
        TerminalSettings.SCHEME_CUSTOM
    };

    private static final String[] DISPLAY_COLOR_SCHEMES = {
        "Solarized Dark",
        "White on Black",
        "Black on White",
        "Custom"
    };

    private static final String[] CURSOR_SHAPES = {
        TerminalSettings.CURSOR_BLINK_BLOCK,
        TerminalSettings.CURSOR_STEADY_BLOCK,
        TerminalSettings.CURSOR_BLINK_UNDERLINE,
        TerminalSettings.CURSOR_STEADY_UNDERLINE,
        TerminalSettings.CURSOR_BLINK_VERTICAL_BAR,
        TerminalSettings.CURSOR_STEADY_VERTICAL_BAR
    };

    private static final String[] DISPLAY_CURSOR_SHAPES = {
        "Block (blinking)",
        "Block (steady)",
        "Underline (blinking)",
        "Underline (steady)",
        "Vertical Bar (blinking)",
        "Vertical Bar (steady)"
    };

    private final boolean isPerProfileMode;
    private final JCheckBox useGlobalCheckbox;
    private final JComboBox<String> colorSchemeCombo;
    private final JComboBox<String> fontFamilyCombo;
    private final JSpinner fontSizeSpinner;
    private final JComboBox<String> cursorShapeCombo;
    private final JSpinner blinkRateSpinner;

    /**
     * Creates a new TerminalSettingsPanel.
     *
     * @param isPerProfileMode if true, shows a "Use global settings" checkbox
     */
    public TerminalSettingsPanel(boolean isPerProfileMode) {
        this.isPerProfileMode = isPerProfileMode;

        setLayout(new BorderLayout());

        Composite composite = new Composite();
        composite.pad(10);
        composite.defaults().space(4);

        if (isPerProfileMode) {
            useGlobalCheckbox = new JCheckBox("Use global terminal settings");
            useGlobalCheckbox.setSelected(true);
            useGlobalCheckbox.addActionListener(e -> updateFieldStates());
            composite.addCell(useGlobalCheckbox).colspan(2).align(Cell.LEFT).space(0, 0, 0, 10);
            composite.row();
        } else {
            useGlobalCheckbox = null;
        }

        // Color scheme
        composite.addCell(new JLabel("Color Scheme:")).align(Cell.LEFT).width(160);
        colorSchemeCombo = new JComboBox<>(DISPLAY_COLOR_SCHEMES);
        composite.addCell(colorSchemeCombo).fillX();
        composite.row();

        // Font family
        composite.addCell(new JLabel("Font:")).align(Cell.LEFT).width(160);
        fontFamilyCombo = createFontFamilyCombo();
        composite.addCell(fontFamilyCombo).fillX();
        composite.row();

        // Font size
        composite.addCell(new JLabel("Font Size:")).align(Cell.LEFT).width(160);
        fontSizeSpinner = new JSpinner(new SpinnerNumberModel(14, 8, 36, 1));
        composite.addCell(fontSizeSpinner).width(80);
        composite.row();

        // Cursor shape
        composite.addCell(new JLabel("Cursor Style:")).align(Cell.LEFT).width(160);
        cursorShapeCombo = new JComboBox<>(DISPLAY_CURSOR_SHAPES);
        composite.addCell(cursorShapeCombo).fillX();
        composite.row();

        // Blink rate
        composite.addCell(new JLabel("Cursor Blink Rate (ms):")).align(Cell.LEFT).width(160);
        blinkRateSpinner = new JSpinner(new SpinnerNumberModel(505, 0, 2000, 50));
        composite.addCell(blinkRateSpinner).width(80);
        composite.row();

        add(composite, BorderLayout.CENTER);
    }

    /**
     * Populates all fields from the given TerminalSettings.
     *
     * @param settings the settings to load
     */
    public void setSettings(TerminalSettings settings) {
        setSelectedDisplayItem(colorSchemeCombo, DISPLAY_COLOR_SCHEMES, COLOR_SCHEMES, settings.getColorScheme());
        if (settings.getFontFamily() == null || settings.getFontFamily().isBlank()) {
            fontFamilyCombo.setSelectedIndex(0);
        } else {
            fontFamilyCombo.setSelectedItem(settings.getFontFamily());
        }
        fontSizeSpinner.setValue(settings.getFontSize());
        setSelectedDisplayItem(cursorShapeCombo, DISPLAY_CURSOR_SHAPES, CURSOR_SHAPES, settings.getCursorShape());
        blinkRateSpinner.setValue(settings.getCursorBlinkRateMs());

        if (isPerProfileMode && useGlobalCheckbox != null) {
            // In per-profile mode, we start with "use global" checked,
            // meaning no override is set. The caller should call setSettings()
            // only when editing an existing override.
            useGlobalCheckbox.setSelected(settings == GlobalSettingsPlaceholder.getGlobal());
        }
    }

    /**
     * Returns the TerminalSettings as configured in this panel.
     *
     * @return the current settings
     */
    public TerminalSettings getSettings() {
        if (isPerProfileMode && useGlobalCheckbox != null && useGlobalCheckbox.isSelected()) {
            return null; // null = use global settings
        }

        TerminalSettings settings = new TerminalSettings();
        settings.setColorScheme(COLOR_SCHEMES[colorSchemeCombo.getSelectedIndex()]);
        settings.setFontFamily(getSelectedFontFamily());
        settings.setFontSize((Integer) fontSizeSpinner.getValue());
        settings.setCursorShape(CURSOR_SHAPES[cursorShapeCombo.getSelectedIndex()]);
        settings.setCursorBlinkRateMs((Integer) blinkRateSpinner.getValue());
        return settings;
    }

    private String getSelectedFontFamily() {
        if (fontFamilyCombo.getSelectedIndex() == 0) {
            return ""; // "System Default"
        }
        return (String) fontFamilyCombo.getSelectedItem();
    }

    private void updateFieldStates() {
        boolean enabled = useGlobalCheckbox == null || !useGlobalCheckbox.isSelected();
        colorSchemeCombo.setEnabled(enabled);
        fontFamilyCombo.setEnabled(enabled);
        fontSizeSpinner.setEnabled(enabled);
        cursorShapeCombo.setEnabled(enabled);
        blinkRateSpinner.setEnabled(enabled);
    }

    private static JComboBox<String> createFontFamilyCombo() {
        String[] systemFonts = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames();
        java.util.Arrays.sort(systemFonts);

        String[] fonts = new String[systemFonts.length + 1];
        fonts[0] = "System Default";
        System.arraycopy(systemFonts, 0, fonts, 1, systemFonts.length);

        JComboBox<String> combo = new JComboBox<>(fonts);
        combo.setEditable(true);
        return combo;
    }

    /**
     * Sets the selected item of a display-value combo box based on the actual value.
     */
    private static void setSelectedDisplayItem(JComboBox<String> combo,
                                                String[] displayValues,
                                                String[] actualValues,
                                                String actualValue) {
        for (int i = 0; i < actualValues.length; i++) {
            if (actualValues[i].equals(actualValue)) {
                combo.setSelectedIndex(i);
                return;
            }
        }
        combo.setSelectedIndex(0);
    }

    /**
     * Placeholder to detect "global" settings in per-profile mode.
     * This is a workaround since we can't compare against GlobalSettings directly
     * from the terminal package without circular dependency concerns.
     */
    private static final class GlobalSettingsPlaceholder {
        static TerminalSettings getGlobal() {
            return null; // Special marker: null means "use global"
        }
    }
}
