package de.in.jnc.connection.filetransfer;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

/**
 * Dual-panel file transfer component with a toolbar and context menus for
 * file operations.
 * <p>
 * Contains a {@link JSplitPane} with a {@link LocalFilePanel} on the left
 * and a {@link RemoteFilePanel} on the right, plus a {@link JToolBar} with
 * unified buttons ordered by function-key sequence. A shared
 * {@link ProgressPanel} is placed below the split pane for both panels.
 * <p>
 * All operations run on background threads via {@link SwingWorker} to keep
 * the UI responsive. Copy and Move operations display animated progress bars
 * (with transfer speed) in the shared {@link ProgressPanel}.
 * <p>
 * Focus tracking: the active panel is determined by the last focused file
 * table. Copy and Move transfer data <i>from</i> the active panel <i>to</i>
 * the other panel. Delete, Rename, and MkDir operate on the active panel only.
 */
public class FileTransferPanel extends JPanel {

    private final LocalFilePanel localPanel;
    private final RemoteFilePanel remotePanel;
    private final ProgressPanel progressPanel;

    /** Tracks which panel's table was last focused (for toolbar button clicks). */
    private AbstractFilePanel lastFocusedPanel;

    /**
     * Creates a new file transfer panel.
     *
     * @param sftpService the SFTP service for remote file operations
     */
    public FileTransferPanel(SftpService sftpService) {
        super(new BorderLayout());

        // Create panels
        localPanel = new LocalFilePanel();
        remotePanel = new RemoteFilePanel(sftpService);
        progressPanel = new ProgressPanel();
        lastFocusedPanel = localPanel;

        // Track focus on both file tables so toolbar buttons know which panel is active
        installFocusTracking(localPanel.getFileTable(), localPanel);
        installFocusTracking(remotePanel.getFileTable(), remotePanel);

        // Toolbar
        JToolBar toolBar = createToolBar();
        add(toolBar, BorderLayout.NORTH);

        // Split pane with panels
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, localPanel, remotePanel);
        splitPane.setResizeWeight(0.5);
        splitPane.setDividerSize(6);
        splitPane.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 4));
        add(splitPane, BorderLayout.CENTER);

        // Shared progress panel below the split pane
        add(progressPanel, BorderLayout.SOUTH);

        setPreferredSize(new Dimension(900, 500));

        // Register keyboard shortcuts on this panel (WHEN_IN_FOCUSED_WINDOW)
        registerKeyboardShortcuts();

        // Register F-key bindings directly on both JTable components
        // (WHEN_ANCESTOR_OF_FOCUSED_COMPONENT) so they always work when
        // a table has focus, even if other components consume the keystroke.
        installTableKeyBindings(localPanel.getFileTable());
        installTableKeyBindings(remotePanel.getFileTable());

        // Install context menus
        installContextMenus();
    }

    // ─── Focus tracking ──────────────────────────────────────────────────

    private void installFocusTracking(JTable table, AbstractFilePanel panel) {
        table.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                lastFocusedPanel = panel;
            }
        });
    }

    private AbstractFilePanel getActivePanel() {
        Component focusOwner = KeyboardFocusManager
                .getCurrentKeyboardFocusManager().getFocusOwner();
        if (focusOwner != null) {
            if (SwingUtilities.isDescendingFrom(focusOwner, remotePanel)) {
                return remotePanel;
            }
            if (SwingUtilities.isDescendingFrom(focusOwner, localPanel)) {
                return localPanel;
            }
        }
        return lastFocusedPanel;
    }

    private boolean isRemoteActive() {
        return getActivePanel() == remotePanel;
    }

    // ─── Toolbar ─────────────────────────────────────────────────────────

    private JToolBar createToolBar() {
        JToolBar toolBar = new JToolBar("File Operations");
        toolBar.setFloatable(false);

        // F2: Rename
        JButton renameBtn = new JButton("F2: Rename");
        renameBtn.setToolTipText("Rename selected file/directory on the active panel");
        renameBtn.addActionListener(this::onRename);
        toolBar.add(renameBtn);

        toolBar.addSeparator();

        // F5: Copy
        JButton copyBtn = new JButton("F5: Copy \u2192");
        copyBtn.setToolTipText("Copy selected items from the active panel to the other panel");
        copyBtn.addActionListener(this::onCopy);
        toolBar.add(copyBtn);

        // F6: Move
        JButton moveBtn = new JButton("F6: Move \u2192");
        moveBtn.setToolTipText("Move selected items from the active panel to the other panel");
        moveBtn.addActionListener(this::onMove);
        toolBar.add(moveBtn);

        toolBar.addSeparator();

        // F7: MkDir
        JButton mkdirBtn = new JButton("F7: MkDir");
        mkdirBtn.setToolTipText("Create a new directory on the active panel");
        mkdirBtn.addActionListener(this::onMkDir);
        toolBar.add(mkdirBtn);

        // F8: Delete
        JButton deleteBtn = new JButton("F8: Delete");
        deleteBtn.setToolTipText("Delete selected items on the active panel");
        deleteBtn.addActionListener(this::onDelete);
        toolBar.add(deleteBtn);

        toolBar.addSeparator();

        // F9: Refresh
        JButton refreshBtn = new JButton("F9: Refresh");
        refreshBtn.setToolTipText("Refresh both panels");
        refreshBtn.addActionListener(this::onRefresh);
        toolBar.add(refreshBtn);

        return toolBar;
    }

    // ─── Context menus ───────────────────────────────────────────────────

    /**
     * Creates and installs context menus on both file tables.
     */
    private void installContextMenus() {
        // Local panel context menu
        JPopupMenu localMenu = new JPopupMenu();
        localMenu.add(createMenuItem("Rename", this::onRename));
        localMenu.add(createMenuItem("Delete", this::onDelete));
        localMenu.add(createMenuItem("Create Directory", this::onMkDir));
        localMenu.addSeparator();
        localMenu.add(createMenuItem("Copy to Remote", this::onCopy));
        localMenu.add(createMenuItem("Move to Remote", this::onMove));
        localPanel.setPopupMenu(localMenu);

        // Remote panel context menu
        JPopupMenu remoteMenu = new JPopupMenu();
        remoteMenu.add(createMenuItem("Rename", this::onRename));
        remoteMenu.add(createMenuItem("Delete", this::onDelete));
        remoteMenu.add(createMenuItem("Create Directory", this::onMkDir));
        remoteMenu.addSeparator();
        remoteMenu.add(createMenuItem("Copy to Local", this::onCopy));
        remoteMenu.add(createMenuItem("Move to Local", this::onMove));
        remotePanel.setPopupMenu(remoteMenu);
    }

    private static JMenuItem createMenuItem(String label, java.util.function.Consumer<ActionEvent> handler) {
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(handler::accept);
        return item;
    }

    // ─── Operation handlers ──────────────────────────────────────────────

    private void executeWorker(SwingWorker<?, ?> worker, Runnable onDone) {
        if (worker == null) {
            return;
        }
        worker.addPropertyChangeListener(evt -> {
            if ("state".equals(evt.getPropertyName())
                    && SwingWorker.StateValue.DONE == evt.getNewValue()) {
                if (onDone != null) {
                    onDone.run();
                }
            }
        });
        worker.execute();
    }

    private void onRename(ActionEvent e) {
        if (isRemoteActive()) {
            executeWorker(
                    FileOperations.createRenameRemoteWorker(remotePanel, this),
                    null);
        } else {
            executeWorker(
                    FileOperations.createRenameLocalWorker(localPanel, this),
                    null);
        }
    }

    private void onCopy(ActionEvent e) {
        if (isRemoteActive()) {
            FileTransferWorker worker = FileOperations.prepareCopyRemoteToLocal(
                    remotePanel, localPanel, this, progressPanel);
            if (worker != null) {
                worker.addPropertyChangeListener(evt -> {
                    if ("state".equals(evt.getPropertyName())
                            && SwingWorker.StateValue.DONE == evt.getNewValue()) {
                        localPanel.refresh();
                        remotePanel.refresh();
                    }
                });
                worker.execute();
            }
        } else {
            FileTransferWorker worker = FileOperations.prepareCopyLocalToRemote(
                    localPanel, remotePanel, this, progressPanel);
            if (worker != null) {
                worker.addPropertyChangeListener(evt -> {
                    if ("state".equals(evt.getPropertyName())
                            && SwingWorker.StateValue.DONE == evt.getNewValue()) {
                        localPanel.refresh();
                        remotePanel.refresh();
                    }
                });
                worker.execute();
            }
        }
    }

    private void onMove(ActionEvent e) {
        if (isRemoteActive()) {
            FileTransferWorker worker = FileOperations.prepareMoveRemoteToLocal(
                    remotePanel, localPanel, this, progressPanel);
            if (worker != null) {
                worker.addPropertyChangeListener(evt -> {
                    if ("state".equals(evt.getPropertyName())
                            && SwingWorker.StateValue.DONE == evt.getNewValue()) {
                        localPanel.refresh();
                        remotePanel.refresh();
                    }
                });
                worker.execute();
            }
        } else {
            FileTransferWorker worker = FileOperations.prepareMoveLocalToRemote(
                    localPanel, remotePanel, this, progressPanel);
            if (worker != null) {
                worker.addPropertyChangeListener(evt -> {
                    if ("state".equals(evt.getPropertyName())
                            && SwingWorker.StateValue.DONE == evt.getNewValue()) {
                        localPanel.refresh();
                        remotePanel.refresh();
                    }
                });
                worker.execute();
            }
        }
    }

    private void onMkDir(ActionEvent e) {
        if (isRemoteActive()) {
            executeWorker(
                    FileOperations.createMkdirRemoteWorker(remotePanel, this),
                    null);
        } else {
            executeWorker(
                    FileOperations.createMkdirLocalWorker(localPanel, this),
                    null);
        }
    }

    private void onDelete(ActionEvent e) {
        if (isRemoteActive()) {
            executeWorker(
                    FileOperations.createDeleteRemoteWorker(remotePanel, this),
                    null);
        } else {
            executeWorker(
                    FileOperations.createDeleteLocalWorker(localPanel, this),
                    null);
        }
    }

    private void onRefresh(ActionEvent e) {
        localPanel.refresh();
        remotePanel.refresh();
    }

    private void onSwitchPanel(ActionEvent e) {
        if (isRemoteActive()) {
            localPanel.getFileTable().requestFocusInWindow();
        } else {
            remotePanel.getFileTable().requestFocusInWindow();
        }
    }

    // ─── Keyboard shortcuts ──────────────────────────────────────────────

    private void registerKeyboardShortcuts() {
        int condition = JComponent.WHEN_IN_FOCUSED_WINDOW;

        bindKey(condition, KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "rename", this::onRename);
        bindKey(condition, KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "copy", this::onCopy);
        bindKey(condition, KeyStroke.getKeyStroke(KeyEvent.VK_F6, 0), "move", this::onMove);
        bindKey(condition, KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0), "mkdir", this::onMkDir);
        bindKey(condition, KeyStroke.getKeyStroke(KeyEvent.VK_F8, 0), "delete", this::onDelete);
        bindKey(condition, KeyStroke.getKeyStroke(KeyEvent.VK_F9, 0), "refresh", this::onRefresh);
        bindKey(condition, KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "switchPanel", this::onSwitchPanel);
    }

    private void bindKey(int condition, KeyStroke keyStroke, String actionName,
                          java.util.function.Consumer<ActionEvent> handler) {
        getInputMap(condition).put(keyStroke, actionName);
        getActionMap().put(actionName, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                handler.accept(e);
            }
        });
    }

    /**
     * Registers F-key bindings directly on a JTable component at
     * {@link JComponent#WHEN_ANCESTOR_OF_FOCUSED_COMPONENT} level.
     * This ensures the shortcuts work even if other components (e.g.
     * JTextField) consume the keystroke at a higher priority.
     */
    private void installTableKeyBindings(JTable table) {
        int condition = JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT;

        table.getInputMap(condition).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "tableRename");
        table.getInputMap(condition).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "tableCopy");
        table.getInputMap(condition).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_F6, 0), "tableMove");
        table.getInputMap(condition).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0), "tableMkdir");
        table.getInputMap(condition).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_F8, 0), "tableDelete");
        table.getInputMap(condition).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_F9, 0), "tableRefresh");
        table.getInputMap(condition).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "tableSwitchPanel");

        table.getActionMap().put("tableRename", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { onRename(e); }
        });
        table.getActionMap().put("tableCopy", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { onCopy(e); }
        });
        table.getActionMap().put("tableMove", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { onMove(e); }
        });
        table.getActionMap().put("tableMkdir", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { onMkDir(e); }
        });
        table.getActionMap().put("tableDelete", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { onDelete(e); }
        });
        table.getActionMap().put("tableRefresh", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { onRefresh(e); }
        });
        table.getActionMap().put("tableSwitchPanel", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { onSwitchPanel(e); }
        });
    }

    // ─── Public accessors ────────────────────────────────────────────────

    public LocalFilePanel getLocalPanel() {
        return localPanel;
    }

    public RemoteFilePanel getRemotePanel() {
        return remotePanel;
    }

    /**
     * Returns the shared progress panel for both local and remote panels.
     *
     * @return the shared progress panel
     */
    public ProgressPanel getProgressPanel() {
        return progressPanel;
    }

    public void refresh() {
        localPanel.refresh();
        remotePanel.refresh();
    }
}
