package de.in.jnc.connection.filetransfer;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.ListSelectionEvent;
import javax.swing.table.TableRowSorter;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A panel that displays the local filesystem in a JTable, supporting
 * navigation (double-click to enter directories, ".." for parent),
 * selection, and refresh.
 */
public class LocalFilePanel extends AbstractFilePanel {

    private static final Logger LOGGER = LogManager.getLogger(LocalFilePanel.class);

    private Path currentPath;

    /**
     * Creates a new local file panel starting at the user's home directory.
     */
    public LocalFilePanel() {
        this(Paths.get(System.getProperty("user.home")));
    }

    /**
     * Creates a new local file panel starting at the given path.
     *
     * @param initialPath the starting directory
     */
    public LocalFilePanel(Path initialPath) {
        super("Local");
        this.currentPath = initialPath.toAbsolutePath().normalize();
        refresh();
    }

    @Override
    protected void onDoubleClick(int rowIndex) {
        FileEntry entry = tableModel.getEntry(rowIndex);
        if ("..".equals(entry.getName())) {
            navigateToParent();
        } else if (entry.isDirectory()) {
            navigateTo(currentPath.resolve(entry.getName()));
        }
    }

    @Override
    public void refresh() {
        loadDirectory(currentPath);
    }

    /**
     * Navigates to the specified directory and refreshes the listing.
     *
     * @param target the directory to enter (must exist and be readable)
     */
    public void navigateTo(Path target) {
        target = target.toAbsolutePath().normalize();
        if (!Files.isDirectory(target)) {
            LOGGER.warn("Not a directory: {}", target);
            return;
        }
        this.currentPath = target;
        loadDirectory(target);
    }

    /**
     * Navigates to the parent directory.
     */
    public void navigateToParent() {
        Path parent = currentPath.getParent();
        if (parent != null) {
            navigateTo(parent);
        }
    }

    /**
     * Returns the current directory path.
     *
     * @return the current path
     */
    public Path getCurrentPath() {
        return currentPath;
    }

    /**
     * Returns the full paths of all selected rows.
     *
     * @return list of selected absolute paths
     */
    public List<Path> getSelectedPaths() {
        int[] rows = fileTable.getSelectedRows();
        List<Path> paths = new ArrayList<>(rows.length);
        for (int row : rows) {
            int modelRow = fileTable.convertRowIndexToModel(row);
            FileEntry entry = tableModel.getEntry(modelRow);
            if (!"..".equals(entry.getName())) {
                paths.add(currentPath.resolve(entry.getName()));
            }
        }
        return paths;
    }

    /**
     * Returns the full path of the single selected entry (or the first selected).
     *
     * @return the selected path, or null if nothing is selected
     */
    public Path getSelectedPath() {
        int row = fileTable.getSelectedRow();
        if (row < 0) {
            return null;
        }
        int modelRow = fileTable.convertRowIndexToModel(row);
        FileEntry entry = tableModel.getEntry(modelRow);
        if ("..".equals(entry.getName())) {
            return currentPath.getParent();
        }
        return currentPath.resolve(entry.getName());
    }

    /**
     * Loads the contents of the given directory into the table model.
     */
    private void loadDirectory(Path dir) {
        List<FileEntry> entries = new ArrayList<>();
        boolean showParent = dir.getParent() != null;

        // List directory contents
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(child, BasicFileAttributes.class);
                    entries.add(FileEntry.fromLocalPath(child, attrs));
                } catch (IOException e) {
                    LOGGER.warn("Cannot read attributes for {}: {}", child, e.getMessage());
                    // Still show the entry with minimal info
                    boolean isDir = Files.isDirectory(child);
                    long size = isDir ? 0 : child.toFile().length();
                    entries.add(new FileEntry(
                            child.getFileName().toString(),
                            isDir,
                            size,
                            0,
                            isDir ? "drwx------" : "-rw-rw-rw-"));
                }
            }
        } catch (IOException e) {
            LOGGER.error("Cannot list directory {}: {}", dir, e.getMessage());
        }

        List<FileEntry> sorted = FileTableModel.sortDirectoriesFirst(entries, showParent);
        tableModel.setEntries(sorted);
        updatePathLabel(dir.toString());
        LOGGER.debug("Loaded {} entries from local {}", entries.size(), dir);
    }

    /**
     * Creates a directory at the given path (relative to current, or absolute).
     *
     * @param dirName the name of the new directory
     * @return true if creation succeeded
     */
    public boolean mkdir(String dirName) {
        Path newDir = currentPath.resolve(dirName);
        try {
            Files.createDirectory(newDir);
            refresh();
            return true;
        } catch (IOException e) {
            LOGGER.error("Cannot create directory {}: {}", newDir, e.getMessage());
            return false;
        }
    }
}
