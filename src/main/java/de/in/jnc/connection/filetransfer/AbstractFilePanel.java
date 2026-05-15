package de.in.jnc.connection.filetransfer;

import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.border.EmptyBorder;

/**
 * Abstract base class for local and remote file panels.
 * <p>
 * Provides a common layout: an editable path field at the top and a JTable with
 * {@link FileTableModel} in a scroll pane. Subclasses implement
 * {@link #onDoubleClick(int)}, {@link #refresh()}, and
 * {@link #navigateToPath(String)}.
 * <p>
 * Supports a {@link JPopupMenu} for right-click context menus, set via
 * {@link #setPopupMenu(JPopupMenu)}.
 */
public abstract class AbstractFilePanel extends JPanel {

    protected final FileTableModel tableModel;
    protected final JTable fileTable;
    protected final JTextField pathField;
    protected final JPanel topPanel;

    private JPopupMenu popupMenu;

    /**
     * Creates a new file panel with the given side label.
     * Permissions column is shown by default.
     *
     * @param sideLabel "Local" or "Remote"
     */
    protected AbstractFilePanel(String sideLabel) {
        this(sideLabel, true);
    }

    /**
     * Creates a new file panel with the given side label and permission visibility.
     *
     * @param sideLabel        "Local" or "Remote"
     * @param showPermissions  whether to include the Permissions column in the table
     */
    protected AbstractFilePanel(String sideLabel, boolean showPermissions) {
        setLayout(new BorderLayout(0, 4));

        // Top: container panel for path field (and optional drive selector)
        topPanel = new JPanel(new BorderLayout(4, 0));
        topPanel.setBorder(new EmptyBorder(2, 4, 2, 4));

        pathField = new JTextField();
        pathField.setEditable(true);
        pathField.setToolTipText("Enter a path and press Enter to navigate");
        topPanel.add(pathField, BorderLayout.CENTER);
        add(topPanel, BorderLayout.NORTH);

        // Center: table
        tableModel = new FileTableModel(showPermissions);
        fileTable = new JTable(tableModel);
        fileTable.setFillsViewportHeight(true);
        fileTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        fileTable.setAutoCreateRowSorter(true);

        // Double-click handler
        fileTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = fileTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        int modelRow = fileTable.convertRowIndexToModel(row);
                        onDoubleClick(modelRow);
                    }
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                showContextMenu(e);
            }

            @Override
            public void mousePressed(MouseEvent e) {
                showContextMenu(e);
            }
        });

        JScrollPane scrollPane = new JScrollPane(fileTable);
        add(scrollPane, BorderLayout.CENTER);

        // Enter key in path field triggers navigation
        pathField.addActionListener((ActionEvent e) -> {
            String text = pathField.getText().trim();
            if (!text.isEmpty()) {
                navigateToPath(text);
            }
        });
    }

    /**
     * Called when a row is double-clicked. Subclasses should handle
     * directory navigation or other actions.
     *
     * @param modelRow the model row index of the double-clicked entry
     */
    protected abstract void onDoubleClick(int modelRow);

    /**
     * Refreshes the file listing for the current directory.
     */
    public abstract void refresh();

    /**
     * Navigates to the given path string (triggered by pressing Enter in the
     * path field). Subclasses should parse the path and navigate accordingly.
     *
     * @param path the path string entered by the user
     */
    protected abstract void navigateToPath(String path);

    /**
     * Returns the underlying table model.
     *
     * @return the file table model
     */
    public FileTableModel getTableModel() {
        return tableModel;
    }

    /**
     * Returns the JTable component.
     *
     * @return the file table
     */
    public JTable getFileTable() {
        return fileTable;
    }

    /**
     * Sets the right-click context menu for this panel's file table.
     *
     * @param popupMenu the popup menu to show on right-click
     */
    public void setPopupMenu(JPopupMenu popupMenu) {
        this.popupMenu = popupMenu;
    }

    /**
     * Returns the current popup menu, if any.
     *
     * @return the popup menu, or null
     */
    public JPopupMenu getPopupMenu() {
        return popupMenu;
    }

    /**
     * Updates the path field text.
     *
     * @param path the current directory path to display
     */
    protected void updatePathField(String path) {
        pathField.setText(path);
    }

    /**
     * Selects the row at the given point (used by context menus to select
     * the right-clicked row without losing multi-selection).
     *
     * @param point the mouse point in table coordinates
     */
    protected void selectRowAtPoint(java.awt.Point point) {
        int row = fileTable.rowAtPoint(point);
        if (row >= 0) {
            // If the row is not already selected, select it (without clearing other selections?
            // Standard behavior: select just this row on right-click if not already part of selection)
            if (!fileTable.getSelectionModel().isSelectedIndex(row)) {
                fileTable.getSelectionModel().setSelectionInterval(row, row);
            }
        }
    }

    /**
     * Shows the context menu if this was a popup trigger event.
     */
    private void showContextMenu(MouseEvent e) {
        if (popupMenu != null && e.isPopupTrigger()) {
            // Select the row under the mouse
            int row = fileTable.rowAtPoint(e.getPoint());
            if (row >= 0) {
                selectRowAtPoint(e.getPoint());
                popupMenu.show(fileTable, e.getX(), e.getY());
            }
        }
    }
}
