package de.in.jnc.connection.filetransfer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.swing.table.AbstractTableModel;

/**
 * Table model for both local and remote file panels.
 * <p>
 * Columns (when permissions are shown): Name, Size, Last Modified, Permissions.<br>
 * Columns (when permissions are hidden): Name, Size, Last Modified.
 * <p>
 * Supports sorting by clicking column headers (managed externally via TableRowSorter).
 */
public class FileTableModel extends AbstractTableModel {

    private static final String[] COLUMN_NAMES_FULL = { "Name", "Size", "Last Modified", "Permissions" };
    private static final String[] COLUMN_NAMES_NO_PERMS = { "Name", "Size", "Last Modified" };
    private static final Class<?>[] COLUMN_TYPES = { String.class, String.class, String.class, String.class };

    private final boolean showPermissions;
    private List<FileEntry> entries = new ArrayList<>();

    /**
     * Creates a model with all columns visible (including Permissions).
     */
    public FileTableModel() {
        this(true);
    }

    /**
     * Creates a model with optional Permissions column.
     *
     * @param showPermissions true to include the Permissions column, false to hide it
     */
    public FileTableModel(boolean showPermissions) {
        this.showPermissions = showPermissions;
    }

    @Override
    public int getRowCount() {
        return entries.size();
    }

    @Override
    public int getColumnCount() {
        return showPermissions ? COLUMN_NAMES_FULL.length : COLUMN_NAMES_NO_PERMS.length;
    }

    @Override
    public String getColumnName(int column) {
        String[] names = showPermissions ? COLUMN_NAMES_FULL : COLUMN_NAMES_NO_PERMS;
        return names[column];
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
        return COLUMN_TYPES[columnIndex];
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        FileEntry entry = entries.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> entry.getName();
            case 1 -> entry.getFormattedSize();
            case 2 -> formatTimestamp(entry.getLastModified());
            case 3 -> entry.getPermissions(); // only reached when showPermissions=true
            default -> "";
        };
    }

    /**
     * Replaces the entire data set and notifies listeners.
     *
     * @param newEntries the new list of file entries
     */
    public void setEntries(List<FileEntry> newEntries) {
        this.entries = new ArrayList<>(newEntries);
        fireTableDataChanged();
    }

    /**
     * Returns the FileEntry at the given row index.
     *
     * @param rowIndex the row
     * @return the FileEntry
     */
    public FileEntry getEntry(int rowIndex) {
        return entries.get(rowIndex);
    }

    /**
     * Returns the raw list of entries (unmodifiable).
     *
     * @return the entries list
     */
    public List<FileEntry> getEntries() {
        return List.copyOf(entries);
    }

    /**
     * Sorts the entries in-place with the given comparator.
     *
     * @param comparator the comparator to sort by
     */
    public void sort(Comparator<FileEntry> comparator) {
        entries.sort(comparator);
        fireTableDataChanged();
    }

    /**
     * Adds the parent directory entry ("..") at the beginning of the list,
     * followed by directories (sorted), then files (sorted).
     *
     * @param rawEntries the unsorted entries from the filesystem
     * @param showParent true if a ".." entry should be prepended
     */
    public static List<FileEntry> sortDirectoriesFirst(List<FileEntry> rawEntries, boolean showParent) {
        List<FileEntry> sorted = new ArrayList<>();
        if (showParent) {
            sorted.add(new FileEntry("..", true, 0, 0, "drwxr-xr-x"));
        }
        // Directories first, then files, each sorted alphabetically
        rawEntries.stream()
                .filter(FileEntry::isDirectory)
                .sorted(Comparator.comparing(FileEntry::getName, String.CASE_INSENSITIVE_ORDER))
                .forEach(sorted::add);
        rawEntries.stream()
                .filter(e -> !e.isDirectory())
                .sorted(Comparator.comparing(FileEntry::getName, String.CASE_INSENSITIVE_ORDER))
                .forEach(sorted::add);
        return sorted;
    }

    private static String formatTimestamp(long millis) {
        if (millis <= 0) {
            return "";
        }
        // Simple date format without external dependency
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
        return sdf.format(new java.util.Date(millis));
    }
}
