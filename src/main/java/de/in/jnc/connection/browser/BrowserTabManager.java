package de.in.jnc.connection.browser;

import java.awt.Component;
import java.awt.FlowLayout;
import java.util.HashMap;
import java.util.Map;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Manages dynamic browser tabs within a {@link JTabbedPane}.
 * <p>
 * Provides open-by-URL semantics: if a URL is already open in a browser tab,
 * that tab is selected instead of creating a duplicate. Also supports opening
 * a fresh empty tab via {@link #openNewTab()}.
 * <p>
 * The first two tab positions (0 = Terminal, 1 = File Transfer) are reserved
 * as pinned tabs and are not managed by this class.
 */
public class BrowserTabManager {

    private static final Logger LOGGER = LogManager.getLogger(BrowserTabManager.class);
    private static final int FIRST_BROWSER_TAB_INDEX = 2;

    private final JTabbedPane tabbedPane;
    private final Map<String, BrowserPanel> urlToPanel;
    private int tabCounter;

    /**
     * Creates a new manager bound to the given tabbed pane.
     *
     * @param tabbedPane the tabbed pane of a {@code ConnectionFrame}
     */
    public BrowserTabManager(JTabbedPane tabbedPane) {
        this.tabbedPane = tabbedPane;
        this.urlToPanel = new HashMap<>();
        this.tabCounter = 0;
    }

    /**
     * Opens the given URL in a browser tab.
     * <p>
     * If a tab with this URL is already open, it is selected and brought to
     * front. Otherwise a new browser tab is created, added to the tabbed pane,
     * and selected.
     *
     * @param url         the URL to open
     * @param displayName the initial tab label
     */
    public void openUrl(String url, String displayName) {
        // Normalise: strip trailing slash for map lookup
        String key = normaliseUrl(url);

        BrowserPanel existing = urlToPanel.get(key);
        if (existing != null) {
            // Tab already exists → select it
            int index = tabbedPane.indexOfComponent(existing);
            if (index >= 0) {
                tabbedPane.setSelectedIndex(index);
                LOGGER.debug("Re-selected existing browser tab for URL: {}", url);
                return;
            }
            // Panel was removed from tabbed pane but still in map – clean up
            urlToPanel.remove(key);
        }

        // Create new browser tab
        BrowserPanel panel = new BrowserPanel(url);
        panel.setNewTabCallback(this::openUrlInNewTab);
        panel.setTitleCallback(title -> onTitleChanged(panel, title));

        urlToPanel.put(key, panel);

        String tabTitle = (displayName != null && !displayName.isEmpty())
                ? displayName
                : "Browser";

        tabbedPane.addTab(tabTitle, panel);
        int newIndex = tabbedPane.indexOfComponent(panel);
        installTabCloseButton(newIndex, tabTitle, panel);
        tabbedPane.setSelectedIndex(newIndex);

        LOGGER.debug("Opened new browser tab [{}] for URL: {}", tabTitle, url);
    }

    /**
     * Opens a fresh empty browser tab with a blank page.
     * <p>
     * Unlike {@link #openUrl(String, String)}, this always creates a new tab
     * and never reuses an existing one.
     */
    public void openNewTab() {
        tabCounter++;
        String tabTitle = "New Tab " + tabCounter;

        BrowserPanel panel = new BrowserPanel("about:blank");
        panel.setNewTabCallback(this::openUrlInNewTab);
        panel.setTitleCallback(title -> onTitleChanged(panel, title));

        tabbedPane.addTab(tabTitle, panel);
        int newIndex = tabbedPane.indexOfComponent(panel);
        installTabCloseButton(newIndex, tabTitle, panel);
        tabbedPane.setSelectedIndex(newIndex);

        LOGGER.debug("Opened new empty browser tab [{}]", tabTitle);
    }

    /**
     * Closes the browser tab at the given index and releases its resources.
     * <p>
     * Tabs at index 0 (Terminal) and 1 (File Transfer) are pinned and cannot
     * be closed through this method.
     *
     * @param tabIndex the tab index to close
     */
    public void closeTab(int tabIndex) {
        if (tabIndex < FIRST_BROWSER_TAB_INDEX || tabIndex >= tabbedPane.getTabCount()) {
            return; // pinned tabs or out of range
        }
        Component component = tabbedPane.getComponentAt(tabIndex);
        if (component instanceof BrowserPanel panel) {
            removeFromMap(panel);
            tabbedPane.remove(tabIndex);
            panel.dispose();
            LOGGER.debug("Closed browser tab at index {}", tabIndex);
        }
    }

    /**
     * Closes all browser tabs and releases their resources.
     * <p>
     * Called when the ConnectionFrame is being closed. Pinned tabs (0 and 1)
     * are not affected.
     */
    public void closeAll() {
        LOGGER.debug("Closing all browser tabs");
        urlToPanel.clear();
        // Iterate backwards to avoid index shifting
        for (int i = tabbedPane.getTabCount() - 1; i >= FIRST_BROWSER_TAB_INDEX; i--) {
            Component component = tabbedPane.getComponentAt(i);
            if (component instanceof BrowserPanel panel) {
                tabbedPane.remove(i);
                panel.dispose();
            }
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────

    /**
     * Installs a custom tab component with a close ("✕") button.
     * The close button uses the panel reference to find the correct tab index
     * even when other tabs have been opened or closed in the meantime.
     */
    private void installTabCloseButton(int tabIndex, String title, BrowserPanel panel) {
        JPanel tabComponent = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        tabComponent.setOpaque(false);
        tabComponent.add(new JLabel(title));
        JButton closeBtn = new JButton("\u2715"); // ✕
        closeBtn.setBorderPainted(false);
        closeBtn.setContentAreaFilled(false);
        closeBtn.setFocusable(false);
        closeBtn.addActionListener(e -> {
            int idx = tabbedPane.indexOfComponent(panel);
            if (idx >= FIRST_BROWSER_TAB_INDEX) {
                closeTab(idx);
            }
        });
        tabComponent.add(closeBtn);
        tabbedPane.setTabComponentAt(tabIndex, tabComponent);
    }

    /**
     * Callback for {@link NewTabCallback}. Derives a display name from the URL
     * and delegates to {@link #openUrl(String, String)}.
     */
    private void openUrlInNewTab(String url) {
        String displayName = url;
        try {
            // Extract hostname for a nicer default display name
            if (url.startsWith("http://") || url.startsWith("https://")) {
                String withoutProtocol = url.substring(url.indexOf("://") + 3);
                int slashPos = withoutProtocol.indexOf('/');
                displayName = (slashPos > 0) ? withoutProtocol.substring(0, slashPos) : withoutProtocol;
                int colonPos = displayName.indexOf(':');
                if (colonPos > 0) {
                    displayName = displayName.substring(0, colonPos);
                }
            }
        } catch (Exception ignored) {
            // fallback: use raw URL
        }
        openUrl(url, displayName);
    }

    private void onTitleChanged(BrowserPanel panel, String title) {
        int index = tabbedPane.indexOfComponent(panel);
        if (index >= 0 && title != null && !title.isEmpty()) {
            tabbedPane.setTitleAt(index, title);
            // Also update the title in the custom tab component
            Component tabComp = tabbedPane.getTabComponentAt(index);
            if (tabComp instanceof JPanel panelWithLabel) {
                for (Component child : panelWithLabel.getComponents()) {
                    if (child instanceof JLabel label) {
                        label.setText(title);
                        break;
                    }
                }
            }
        }
    }

    private void removeFromMap(BrowserPanel panel) {
        urlToPanel.entrySet()
                .removeIf(entry -> entry.getValue() == panel);
    }

    /**
     * Normalises a URL for use as a map key.
     * Strips trailing slashes to treat {@code "http://foo/"} and
     * {@code "http://foo"} as the same.
     */
    private static String normaliseUrl(String url) {
        if (url == null) {
            return "";
        }
        String key = url.trim();
        // Strip trailing slash (but keep "about:blank" as-is)
        if (key.endsWith("/") && !key.equals("about:blank/")) {
            key = key.substring(0, key.length() - 1);
        }
        return key;
    }
}
