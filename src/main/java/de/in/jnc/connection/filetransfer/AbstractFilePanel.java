package de.in.jnc.connection.filetransfer;

import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.TableRowSorter;

/**
 * Abstract base class for local and remote file panels.
 * <p>
 * Provides a common layout: a path label at the top and a JTable with
 * {@link FileTableModel} in a scroll pane. Concrete subclasses implement
 * {@link #onDoubleClick(int)} and {@link #refresh()}.
 */
public abstract class AbstractFilePanel extends JPanel {

    protected final FileTableModel tableModel;
    protected final JTable fileTable;
    protected final JLabel pathLabel;

    /**
     * Creates a new file panel with the given side label.
     *
     * @param sideLabel "Local" or "Remote"
     */
    protected AbstractFilePanel(String sideLabel) {
        setLayout(new BorderLayout(0, 4));

        // Top: path label
        pathLabel = new JLabel(sideLabel + ": ");
        pathLabel.setHorizontalAlignment(SwingConstants.LEFT);
        pathLabel.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 4, 2, 4));
        add(pathLabel, BorderLayout.NORTH);

        // Center: table
        tableModel = new FileTableModel();
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
        });

        JScrollPane scrollPane = new JScrollPane(fileTable);
        add(scrollPane, BorderLayout.CENTER);
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
     * Updates the path label display text.
     *
     * @param path the current directory path to display
     */
    protected void updatePathLabel(String path) {
        pathLabel.setText(path);
    }
}
