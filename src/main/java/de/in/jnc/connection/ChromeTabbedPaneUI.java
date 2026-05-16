package de.in.jnc.connection;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;

import javax.swing.JComponent;
import javax.swing.UIManager;
import javax.swing.plaf.ComponentUI;

import com.formdev.flatlaf.ui.FlatTabbedPaneUI;

/**
 * A custom {@link FlatTabbedPaneUI} that renders tabs in a Chrome-like style:
 * trapezoidal shape with smooth curved transitions and <b>concave foot
 * curves</b> (the characteristic "ears" where the tab meets the tab strip).
 * <p>
 * The tab geometry closely follows real Chrome tabs:
 * <pre>
 *         ╭────────────────────╮          ← convex top corners (small radius)
 *        ╱                      ╲         ← gentle slope
 *       ╱                        ╲
 *   ╮──╯                          ╰──╭   ← concave "foot" curves (the ears)
 *   ╰━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━╯   ← tab strip baseline
 * </pre>
 * <p>
 * Features:
 * <ul>
 *   <li>Concave bottom corners that flow smoothly into the tab strip</li>
 *   <li>Selected tab uses FlatLaf's theme {@code selectedBackground}</li>
 *   <li>Unselected tabs are slightly brighter than the pane background</li>
 *   <li>Selected tab has a top accent line (FlatLaf accent color)</li>
 *   <li>Tab width is capped at {@link #MAX_TAB_WIDTH} pixels (Chrome behavior)</li>
 *   <li>No separate tab border — the shape itself provides the visual boundary</li>
 * </ul>
 */
public class ChromeTabbedPaneUI extends FlatTabbedPaneUI {

    /** Maximum tab width in pixels, mimicking Chrome's tab width cap. */
    public static final int MAX_TAB_WIDTH = 220;

    /** Radius of the convex curves at the top corners of the tab. */
    private static final float TOP_CORNER_RADIUS = 8f;

    /** Radius of the concave curves at the bottom corners (the "ears"). */
    private static final float FOOT_CURVE_RADIUS = 6f;

    /** Width of the diagonal slope on each side of the tab. */
    private static final float SLOPE_WIDTH = 14f;

    /** Height of the accent line drawn at the top of the selected tab. */
    private static final float ACCENT_LINE_THICKNESS = 2.5f;

    /** Border stroke width for unselected tabs. */
    private static final float BORDER_STROKE_WIDTH = 1f;

    /** Brightness offset added to unselected tab backgrounds. */
    private static final int UNSELECTED_BRIGHTNESS_OFFSET = 15;

    public static ComponentUI createUI(JComponent c) {
        return new ChromeTabbedPaneUI();
    }

    @Override
    protected void installDefaults() {
        super.installDefaults();
        tabInsets = new Insets(3, 10, 3, 10);
        selectedTabPadInsets = new Insets(0, 0, 0, 0);
    }

    @Override
    protected void paintTabBackground(Graphics g, int tabPlacement, int tabIndex,
                                      int x, int y, int w, int h, boolean isSelected) {

        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            Color bg = resolveTabBackground(isSelected);
            g2.setColor(bg);

            Path2D.Float path = buildTabShape(x, y, w, h);
            g2.fill(path);

            if (isSelected) {
                paintSelectedAccentLine(g2, x, y, w);
            } else {
                paintUnselectedBorder(g2, path);
            }
        } finally {
            g2.dispose();
        }
    }

    @Override
    protected void paintTabBorder(Graphics g, int tabPlacement, int tabIndex,
                                  int x, int y, int w, int h, boolean isSelected) {
        // No separate border — the shape drawn in paintTabBackground is sufficient
    }

    @Override
    protected int calculateTabWidth(int tabPlacement, int tabIndex, FontMetrics metrics) {
        int width = super.calculateTabWidth(tabPlacement, tabIndex, metrics);
        return Math.min(width, MAX_TAB_WIDTH);
    }

    // ── Shape construction ──────────────────────────────────────────────

    /**
     * Builds the Chrome-like tab shape with concave foot curves.
     * <p>
     * The path is constructed clockwise starting from the bottom-left:
     * <ol>
     *   <li>Concave curve up from baseline (left ear)</li>
     *   <li>Diagonal slope going up and right</li>
     *   <li>Convex curve at top-left corner</li>
     *   <li>Straight top edge</li>
     *   <li>Convex curve at top-right corner</li>
     *   <li>Diagonal slope going down and right</li>
     *   <li>Concave curve down to baseline (right ear)</li>
     *   <li>Close path along baseline</li>
     * </ol>
     */
    private static Path2D.Float buildTabShape(int x, int y, int w, int h) {
        Path2D.Float path = new Path2D.Float();

        float left = x;
        float right = x + w;
        float top = y;
        float bottom = y + h;

        float foot = FOOT_CURVE_RADIUS;
        float slope = SLOPE_WIDTH;
        float topR = TOP_CORNER_RADIUS;

        // Start: bottom-left (on the baseline, before the left ear)
        path.moveTo(left - foot, bottom);

        // Left concave "ear": curves upward from the baseline
        path.quadTo(left, bottom, left, bottom - foot);

        // Left diagonal slope up to the top-left corner area
        path.lineTo(left + slope - topR, top + topR);

        // Top-left convex corner
        path.quadTo(left + slope, top, left + slope + topR, top);

        // Top straight edge
        path.lineTo(right - slope - topR, top);

        // Top-right convex corner
        path.quadTo(right - slope, top, right - slope + topR, top + topR);

        // Right diagonal slope down to the bottom-right area
        path.lineTo(right, bottom - foot);

        // Right concave "ear": curves outward to the baseline
        path.quadTo(right, bottom, right + foot, bottom);

        path.closePath();
        return path;
    }

    // ── Painting helpers ────────────────────────────────────────────────

    /**
     * Resolves the background color for a tab.
     */
    private Color resolveTabBackground(boolean isSelected) {
        if (isSelected) {
            Color bg = selectedBackground != null
                    ? selectedBackground
                    : UIManager.getColor("TabbedPane.selectedBackground");
            if (bg == null) {
                bg = tabPane.getBackground().brighter();
            }
            return bg;
        }
        Color bg = tabPane.getBackground();
        return new Color(
                Math.min(255, bg.getRed() + UNSELECTED_BRIGHTNESS_OFFSET),
                Math.min(255, bg.getGreen() + UNSELECTED_BRIGHTNESS_OFFSET),
                Math.min(255, bg.getBlue() + UNSELECTED_BRIGHTNESS_OFFSET),
                bg.getAlpha());
    }

    /**
     * Draws a thin accent line at the top of the selected tab
     * (similar to Chrome's colored tab indicator).
     */
    private void paintSelectedAccentLine(Graphics2D g2, int x, int y, int w) {
        Color accent = UIManager.getColor("Component.accentColor");
        if (accent == null) {
            accent = UIManager.getColor("TabbedPane.underlineColor");
        }
        if (accent != null) {
            g2.setColor(accent);
            g2.setStroke(new BasicStroke(ACCENT_LINE_THICKNESS, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            float topR = TOP_CORNER_RADIUS;
            float slope = SLOPE_WIDTH;
            float lineY = y + ACCENT_LINE_THICKNESS / 2f;

            g2.drawLine(
                    Math.round(x + slope + topR), Math.round(lineY),
                    Math.round(x + w - slope - topR), Math.round(lineY));
        }
    }

    /**
     * Draws a subtle border around unselected tabs for visual separation.
     */
    private void paintUnselectedBorder(Graphics2D g2, Path2D.Float path) {
        Color borderCol = contentAreaColor != null
                ? contentAreaColor
                : UIManager.getColor("TabbedPane.contentAreaColor");
        if (borderCol != null) {
            g2.setColor(new Color(
                    borderCol.getRed(), borderCol.getGreen(),
                    borderCol.getBlue(), 80));
            g2.setStroke(new BasicStroke(BORDER_STROKE_WIDTH));
            g2.draw(path);
        }
    }
}
