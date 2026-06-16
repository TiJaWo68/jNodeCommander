package de.in.jnc.connection.browser;

import java.awt.event.ActionEvent;
import java.util.Collections;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.in.jnc.ConnectionProfile;
import de.in.jnc.connection.ConnectionFrame;

/**
 * Browser menu for the ConnectionFrame tab strip.
 */
public final class BrowserMenu extends JPopupMenu {

    private static final Logger LOGGER = LogManager.getLogger(BrowserMenu.class);
    private final ConnectionFrame frame;

    public BrowserMenu(ConnectionFrame frame) {
        this.frame = frame;
    }

    public void rebuild() {
        removeAll();

        add(new JMenuItem(new AbstractAction("\uD83D\uDF95  New Browser Tab") {
            public void actionPerformed(ActionEvent e) {
                frame.openNewBrowserTab();
            }
        }));

        addSeparator();

        // Bookmarks submenu
        ConnectionProfile profile = frame.getProfile();
        JMenu bookmarksMenu = new JMenu("\uD83D\uDCD1  Bookmarks");
        List<Bookmark> bookmarks = (profile != null)
                ? profile.getBookmarks() : Collections.<Bookmark>emptyList();
        Collections.sort(bookmarks);
        if (bookmarks.isEmpty()) {
            JMenuItem empty = new JMenuItem("(no bookmarks)");
            empty.setEnabled(false);
            bookmarksMenu.add(empty);
        } else {
            for (final Bookmark bm : bookmarks) {
                bookmarksMenu.add(new JMenuItem(new AbstractAction(bm.toString()) {
                    public void actionPerformed(ActionEvent e) {
                        frame.openBrowserUrl(bm.getUrl(), bm.getTitle());
                    }
                }));
            }
        }
        add(bookmarksMenu);

        // History
        add(new JMenuItem(new AbstractAction("\uD83D\uDD50  History") {
            public void actionPerformed(ActionEvent e) {
                openHistoryTab();
            }
        }));
        add(new JMenuItem(new AbstractAction("\uD83D\uDDD1  Clear History") {
            public void actionPerformed(ActionEvent e) {
                frame.clearHistory();
            }
        }));

        // Open Tabs submenu
        JMenu openTabsMenu = new JMenu("\uD83D\uDCCB  Open Tabs");
        int tabCount = frame.getTabbedPane().getTabCount();
        int browserTabs = 0;
        for (int i = 2; i < tabCount; i++) {
            String title = frame.getTabbedPane().getTitleAt(i);
            if (title == null || title.isEmpty()) {
                title = "Browser Tab " + (i - 1);
            }
            final int idx = i;
            openTabsMenu.add(new JMenuItem(new AbstractAction(title) {
                public void actionPerformed(ActionEvent e) {
                    frame.getTabbedPane().setSelectedIndex(idx);
                }
            }));
            browserTabs++;
        }
        if (browserTabs == 0) {
            JMenuItem empty = new JMenuItem("(no open browser tabs)");
            empty.setEnabled(false);
            openTabsMenu.add(empty);
        }
        add(openTabsMenu);

        addSeparator();

        // Discover Endpoints
        add(new JMenuItem(new AbstractAction("\uD83D\uDD17  Discover Endpoints") {
            public void actionPerformed(ActionEvent e) {
                frame.showEndpointDiscovery();
            }
        }));
    }

    private void openHistoryTab() {
        ConnectionProfile profile = frame.getProfile();
        if (profile == null) return;
        // Reuse existing History tab if already open
        if (frame.selectExistingPanelTab("History")) return;
        HistoryPanel panel = new HistoryPanel(profile,
                url -> frame.openBrowserUrl(url, url));
        frame.openPanelTab("History", panel);
    }

}
