package de.in.jnc.connection.filetransfer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JOptionPane;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A panel that displays the remote filesystem (via SFTP) in a JTable,
 * supporting navigation (double-click to enter directories, ".." for parent),
 * selection, and refresh.
 */
public class RemoteFilePanel extends AbstractFilePanel {

    private static final Logger LOGGER = LogManager.getLogger(RemoteFilePanel.class);

    private final SftpService sftpService;
    private String currentPath;

    /**
     * Creates a new remote file panel connected via the given SftpService.
     *
     * @param sftpService the SFTP service to use for remote operations
     */
    public RemoteFilePanel(SftpService sftpService) {
        super("Remote");
        this.sftpService = sftpService;
        this.currentPath = "/";
        refresh();
    }

    @Override
    protected void onDoubleClick(int rowIndex) {
        FileEntry entry = tableModel.getEntry(rowIndex);
        if ("..".equals(entry.getName())) {
            navigateToParent();
        } else if (entry.isDirectory()) {
            String newPath = currentPath.endsWith("/")
                    ? currentPath + entry.getName()
                    : currentPath + "/" + entry.getName();
            navigateTo(newPath);
        }
    }

    @Override
    public void refresh() {
        loadDirectory(currentPath);
    }

    /**
     * Navigates to the specified remote directory and refreshes the listing.
     *
     * @param target the absolute remote path to enter
     */
    public void navigateTo(String target) {
        // Normalize path
        target = target.replace('\\', '/');
        if (!target.startsWith("/")) {
            target = "/" + target;
        }
        // Remove trailing slash unless it's just "/"
        if (target.length() > 1 && target.endsWith("/")) {
            target = target.substring(0, target.length() - 1);
        }
        this.currentPath = target;
        loadDirectory(target);
    }

    /**
     * Navigates to the parent directory.
     */
    public void navigateToParent() {
        if ("/".equals(currentPath)) {
            return; // Already at root
        }
        int lastSlash = currentPath.lastIndexOf('/');
        if (lastSlash <= 0) {
            navigateTo("/");
        } else {
            navigateTo(currentPath.substring(0, lastSlash));
        }
    }

    /**
     * Returns the current remote directory path.
     *
     * @return the current path
     */
    public String getCurrentPath() {
        return currentPath;
    }

    /**
     * Returns the full remote paths of all selected rows.
     *
     * @return list of selected absolute remote paths
     */
    public List<String> getSelectedPaths() {
        int[] rows = fileTable.getSelectedRows();
        List<String> paths = new ArrayList<>(rows.length);
        for (int row : rows) {
            int modelRow = fileTable.convertRowIndexToModel(row);
            FileEntry entry = tableModel.getEntry(modelRow);
            if (!"..".equals(entry.getName())) {
                String remotePath = currentPath.endsWith("/")
                        ? currentPath + entry.getName()
                        : currentPath + "/" + entry.getName();
                paths.add(remotePath);
            }
        }
        return paths;
    }

    /**
     * Returns the full remote path of the single selected entry (or the first selected).
     *
     * @return the selected path, or null if nothing is selected
     */
    public String getSelectedPath() {
        int row = fileTable.getSelectedRow();
        if (row < 0) {
            return null;
        }
        int modelRow = fileTable.convertRowIndexToModel(row);
        FileEntry entry = tableModel.getEntry(modelRow);
        if ("..".equals(entry.getName())) {
            return getParentPath(currentPath);
        }
        return currentPath.endsWith("/")
                ? currentPath + entry.getName()
                : currentPath + "/" + entry.getName();
    }

    /**
     * Returns the parent path for the given remote path.
     */
    private static String getParentPath(String path) {
        if ("/".equals(path)) {
            return "/";
        }
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash <= 0) {
            return "/";
        }
        return path.substring(0, lastSlash);
    }

    /**
     * Loads the contents of the given remote directory into the table model.
     */
    private void loadDirectory(String path) {
        try {
            List<FileEntry> entries = sftpService.listFiles(path);
            boolean showParent = !"/".equals(path);
            List<FileEntry> sorted = FileTableModel.sortDirectoriesFirst(entries, showParent);
            tableModel.setEntries(sorted);
            updatePathLabel(path);
            LOGGER.debug("Loaded {} entries from remote {}", entries.size(), path);
        } catch (IOException e) {
            LOGGER.error("Cannot list remote directory {}: {}", path, e.getMessage());
            tableModel.setEntries(List.of());
            JOptionPane.showMessageDialog(this,
                    "Cannot list remote directory:\n" + e.getMessage(),
                    "SFTP Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Returns the SftpService instance used by this panel.
     *
     * @return the SFTP service
     */
    public SftpService getSftpService() {
        return sftpService;
    }

    /**
     * Creates a remote directory in the current path.
     *
     * @param dirName the name of the new directory
     * @return true if creation succeeded
     */
    public boolean mkdir(String dirName) {
        String newDir = currentPath.endsWith("/")
                ? currentPath + dirName
                : currentPath + "/" + dirName;
        try {
            sftpService.mkdir(newDir);
            refresh();
            return true;
        } catch (IOException e) {
            LOGGER.error("Cannot create remote directory {}: {}", newDir, e.getMessage());
            return false;
        }
    }
}
