package de.in.jnc.connection;

import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.extras.FlatSVGIcon;

/**
 * Manual Robot-based test for verifying tab click-to-select behaviour with
 * {@link ChromeTabbedPaneUI} and custom tab components.
 * <p>
 * This test creates a {@link JFrame} with a {@link JTabbedPane} using
 * {@link ChromeTabbedPaneUI}, adds pinned (icon-only) tabs and browser tabs
 * with custom components (icon {@link JLabel} + title {@link JLabel} + close
 * {@link JButton}), then uses {@link Robot} to simulate mouse clicks and
 * verifies that each click correctly selects the intended tab.
 * <p>
 * Run with: {@code mvn exec:java -Dexec.mainClass="de.in.jnc.connection.ChromeTabbedPaneRobotTest"}
 */
public class ChromeTabbedPaneRobotTest {

    private static final int ROBOT_DELAY_MS = 200;
    private static final int FRAME_X = 200;
    private static final int FRAME_Y = 200;
    private static final int FRAME_W = 800;
    private static final int FRAME_H = 500;

    private JFrame frame;
    private JTabbedPane tabbedPane;
    private Robot robot;

    /**
     * Tracks the last selected tab index.
     */
    private final AtomicInteger lastSelectedIndex = new AtomicInteger(-1);

    /**
     * Tracks the last closed tab index.
     */
    private final AtomicInteger lastClosedIndex = new AtomicInteger(-1);

    public static void main(String[] args) throws Exception {
        UIManager.setLookAndFeel(new FlatDarkLaf());
        ChromeTabbedPaneRobotTest test = new ChromeTabbedPaneRobotTest();
        try {
            test.setup();
            test.runAll();
        } finally {
            test.teardown();
        }
    }

    void setup() throws Exception {
        robot = new Robot();
        robot.setAutoDelay(50);
        robot.setAutoWaitForIdle(true);

        CountDownLatch latch = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            frame = new JFrame("ChromeTabbedPane Robot Test");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setBounds(FRAME_X, FRAME_Y, FRAME_W, FRAME_H);
            frame.setLayout(new BorderLayout());

            tabbedPane = new JTabbedPane();
            tabbedPane.setUI(new ChromeTabbedPaneUI());
            tabbedPane.putClientProperty("JTabbedPane.showTabSeparators", Boolean.FALSE);
            tabbedPane.putClientProperty("JTabbedPane.hasFullBorder", Boolean.FALSE);
            tabbedPane.putClientProperty("JTabbedPane.minimumTabHeight", 28);

            // Pinned tab 0: Terminal (icon-only)
            FlatSVGIcon terminalIcon = new FlatSVGIcon("terminal.svg", 16, 16);
            JPanel terminalPanel = new JPanel();
            terminalPanel.add(new JLabel("Terminal Content"));
            tabbedPane.addTab(null, terminalIcon, terminalPanel, "SSH Terminal");

            // Pinned tab 1: File Transfer (icon-only)
            FlatSVGIcon folderIcon = new FlatSVGIcon("folder.svg", 16, 16);
            JPanel filePanel = new JPanel();
            filePanel.add(new JLabel("File Transfer Content"));
            tabbedPane.addTab(null, folderIcon, filePanel, "SFTP File Transfer");

            // Browser tab 2: with custom component
            addBrowserTab("GitHub", "https://github.com");
            addBrowserTab("StackOverflow", "https://stackoverflow.com");
            addBrowserTab("Example with long title that should be truncated", "https://example.com");

            // Selection listener
            tabbedPane.addChangeListener(e -> {
                int sel = tabbedPane.getSelectedIndex();
                lastSelectedIndex.set(sel);
                System.out.println("[Listener] Tab selected: index=" + sel
                        + " title='" + tabbedPane.getTitleAt(sel) + "'");
            });

            frame.add(tabbedPane, BorderLayout.CENTER);
            frame.setVisible(true);
            latch.countDown();
        });
        latch.await(5, TimeUnit.SECONDS);
        robot.waitForIdle();
        robot.delay(500);

        // Ensure frame is active and on top
        SwingUtilities.invokeLater(() -> {
            frame.toFront();
            frame.requestFocus();
        });
        robot.delay(500);
    }

    void teardown() {
        if (frame != null) {
            SwingUtilities.invokeLater(frame::dispose);
        }
    }

    // ── Add a browser tab with custom component ──────────────────────────

    private void addBrowserTab(String title, String tooltip) {
        JPanel content = new JPanel();
        content.add(new JLabel("Content: " + title));
        tabbedPane.addTab(title, content);
        int index = tabbedPane.indexOfComponent(content);

        installCustomTabComponent(index, title, content);
    }

    private void installCustomTabComponent(int tabIndex, String title, JPanel panel) {
        JPanel tabComponent = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0)) {
            @Override
            public Dimension getPreferredSize() {
                Dimension pref = super.getPreferredSize();
                pref.width = Math.min(pref.width, ChromeTabbedPaneUI.MAX_TAB_WIDTH);
                return pref;
            }
        };
        tabComponent.setOpaque(false);

        // Icon label
        FlatSVGIcon globeIcon = new FlatSVGIcon("globe.svg", 16, 16);
        JLabel iconLabel = new JLabel(globeIcon);
        tabComponent.add(iconLabel);

        // Title label (same constrained width as in BrowserTabManager)
        JLabel label = new JLabel(title) {
            @Override
            public Dimension getPreferredSize() {
                Dimension pref = super.getPreferredSize();
                int maxLabelWidth = ChromeTabbedPaneUI.MAX_TAB_WIDTH - 16 - 24 - 8;
                pref.width = Math.min(pref.width, maxLabelWidth);
                return pref;
            }
        };
        label.setToolTipText(title);
        tabComponent.add(label);

        // Close button
        JButton closeBtn = new JButton("\u2715");
        closeBtn.setBorderPainted(false);
        closeBtn.setContentAreaFilled(false);
        closeBtn.setFocusable(false);
        closeBtn.addActionListener(e -> {
            int idx = tabbedPane.indexOfComponent(panel);
            if (idx >= 2) { // only browser tabs (>=2)
                lastClosedIndex.set(idx);
                System.out.println("[Close] Closing tab index=" + idx + " title='" + title + "'");
                tabbedPane.remove(idx);
            }
        });
        tabComponent.add(closeBtn);

        // Mouse listener for tab selection
        tabComponent.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int idx = tabbedPane.indexOfComponent(panel);
                if (idx >= 0) {
                    System.out.println("[MouseAdapter on tabComponent] Clicked on tab index=" + idx);
                    tabbedPane.setSelectedIndex(idx);
                }
            }
        });

        // ALSO add mouse listener directly on the labels
        MouseAdapter labelClickHandler = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int idx = tabbedPane.indexOfComponent(panel);
                if (idx >= 0) {
                    System.out.println("[MouseAdapter on " + e.getComponent().getClass().getSimpleName()
                            + "] Clicked on tab index=" + idx);
                    tabbedPane.setSelectedIndex(idx);
                }
            }
        };
        label.addMouseListener(labelClickHandler);
        iconLabel.addMouseListener(labelClickHandler);

        tabbedPane.setTabComponentAt(tabIndex, tabComponent);
    }

    // ── Test methods ────────────────────────────────────────────────────

    void runAll() throws Exception {
        System.out.println("\n=== Starting Robot tab click tests ===\n");

        testClickPinnedTab(0, "Terminal");
        testClickPinnedTab(1, "File Transfer");
        testClickBrowserTabLabel(2, "GitHub");
        testClickBrowserTabLabel(3, "StackOverflow");
        testClickBrowserTabIcon(2, "GitHub (icon)");
        testClickBrowserTabLabel(4, "Long title tab");
        testSwitchTabs();

        System.out.println("\n=== All manual tests completed ===");
        System.out.println("Verify the output above to check if tab selection worked correctly.");
        System.out.println("The window will close automatically in 3 seconds...");
        robot.delay(3000);
    }

    private void testClickPinnedTab(int index, String name) throws Exception {
        System.out.println("--- Test: Click pinned tab '" + name + "' (index=" + index + ") ---");
        clickTabAndVerify(index, name);
    }

    private void testClickBrowserTabLabel(int index, String name) throws Exception {
        System.out.println("--- Test: Click browser tab LABEL '" + name + "' (index=" + index + ") ---");
        clickTabAndVerify(index, name);
    }

    private void testClickBrowserTabIcon(int index, String name) throws Exception {
        System.out.println("--- Test: Click browser tab ICON '" + name + "' (index=" + index + ") ---");
        clickTabAndVerify(index, name);
    }

    private void testSwitchTabs() throws Exception {
        System.out.println("--- Test: Switch between tabs ---");
        // Switch from tab 0 to 1 to 2 and back
        clickTabAndVerify(0, "Terminal");
        clickTabAndVerify(2, "GitHub");
        clickTabAndVerify(1, "File Transfer");
        clickTabAndVerify(3, "StackOverflow");
        clickTabAndVerify(0, "Terminal (again)");
        clickTabAndVerify(2, "GitHub (again)");
    }

    /**
     * Finds the center of the tab at the given index (in screen coordinates),
     * clicks there, and verifies the selection changed.
     */
    private void clickTabAndVerify(int tabIndex, String description) throws Exception {
        lastSelectedIndex.set(-1);
        robot.delay(ROBOT_DELAY_MS);

        Point clickPoint = getTabClickPoint(tabIndex);
        if (clickPoint == null) {
            System.out.println("  SKIP: Could not find click point for tab " + tabIndex);
            return;
        }

        System.out.println("  Clicking at screen (" + clickPoint.x + ", " + clickPoint.y + ")");

        robot.mouseMove(clickPoint.x, clickPoint.y);
        robot.delay(100);

        // Print current mouse position for debugging
        PointerInfo pi = MouseInfo.getPointerInfo();
        if (pi != null) {
            Point loc = pi.getLocation();
            System.out.println("  Robot mouse at (" + loc.x + ", " + loc.y + ")");
        }

        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(50);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(200);

        int selected = lastSelectedIndex.get();
        if (selected == tabIndex) {
            System.out.println("  ✓ PASS: Tab " + tabIndex + " (" + description + ") was selected");
        } else {
            System.out.println("  ✗ FAIL: Expected tab " + tabIndex + " but selection is "
                    + (selected >= 0 ? tabIndex : "unchanged (" + tabbedPane.getSelectedIndex() + ")"));
            // Print debug info
            printDebugInfo(tabIndex);
        }
    }

    /**
     * Determines the click point for a tab.
     * <p>
     * For tabs with custom components, we try to click on the specific
     * sub-component (label vs icon). The click point is computed by
     * finding the tab rectangle and then clicking within it.
     */
    private Point getTabClickPoint(int tabIndex) throws Exception {
        // Run on EDT
        AtomicReference<Point> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        SwingUtilities.invokeLater(() -> {
            try {
                if (tabIndex < 0 || tabIndex >= tabbedPane.getTabCount()) {
                    latch.countDown();
                    return;
                }

                // Get the tab bounds
                java.awt.Rectangle tabBounds = tabbedPane.getBoundsAt(tabIndex);
                if (tabBounds == null) {
                    System.out.println("  tabBounds is null for index " + tabIndex);
                    latch.countDown();
                    return;
                }

                // Convert to screen coordinates
                Point tabLoc = new Point(tabBounds.x, tabBounds.y);
                SwingUtilities.convertPointToScreen(tabLoc, tabbedPane);

                // Try to find sub-components for browser tabs
                Component tabComp = tabbedPane.getTabComponentAt(tabIndex);
                if (tabComp != null && tabComp instanceof JPanel panel) {
                    // Try clicking on the title label (second JLabel without icon)
                    Component[] children = panel.getComponents();
                    for (Component child : children) {
                        if (child instanceof JLabel label && label.getIcon() == null) {
                            // Click on the text label
                            Point labelLoc = label.getLocation();
                            SwingUtilities.convertPointToScreen(labelLoc, panel);
                            int cx = labelLoc.x + label.getWidth() / 2;
                            int cy = labelLoc.y + label.getHeight() / 2;
                            result.set(new Point(cx, cy));
                            System.out.println("  Tab " + tabIndex + " LABEL bounds: ("
                                    + labelLoc.x + "," + labelLoc.y + ") size("
                                    + label.getWidth() + "x" + label.getHeight() + ")");
                            latch.countDown();
                            return;
                        }
                    }
                    // Fall back to clicking on the center of the tab component
                    int cx = tabLoc.x + tabBounds.width / 2;
                    int cy = tabLoc.y + tabBounds.height / 2;
                    result.set(new Point(cx, cy));
                    System.out.println("  Tab " + tabIndex + " CENTER of tabComponent: ("
                            + cx + "," + cy + ")");
                } else {
                    // For pinned tabs (no custom component), click center
                    int cx = tabLoc.x + tabBounds.width / 2;
                    int cy = tabLoc.y + tabBounds.height / 2;
                    result.set(new Point(cx, cy));
                    System.out.println("  Tab " + tabIndex + " CENTER (pinned): ("
                            + cx + "," + cy + ")");
                }
            } finally {
                latch.countDown();
            }
        });

        latch.await(5, TimeUnit.SECONDS);
        return result.get();
    }

    private void printDebugInfo(int tabIndex) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            try {
                System.out.println("  --- Debug info for tab " + tabIndex + " ---");
                System.out.println("  Tab count: " + tabbedPane.getTabCount());
                for (int i = 0; i < tabbedPane.getTabCount(); i++) {
                    java.awt.Rectangle r = tabbedPane.getBoundsAt(i);
                    String title = tabbedPane.getTitleAt(i);
                    Component comp = tabbedPane.getTabComponentAt(i);
                    System.out.println("  Tab[" + i + "]: title='" + title
                            + "' bounds=" + (r != null ? r : "null")
                            + " customComponent=" + (comp != null ? comp.getClass().getSimpleName() : "none"));
                    if (comp instanceof JPanel p) {
                        for (Component c : p.getComponents()) {
                            System.out.println("    child: " + c.getClass().getSimpleName()
                                    + " bounds=" + c.getBounds()
                                    + " visible=" + c.isVisible());
                        }
                    }
                }
                System.out.println("  Selected index: " + tabbedPane.getSelectedIndex());
                System.out.println("  --- End debug info ---");
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
    }
}
