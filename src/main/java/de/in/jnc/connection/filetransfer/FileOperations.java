package de.in.jnc.connection.filetransfer;

import java.awt.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import javax.swing.JOptionPane;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Static utility methods for file operations (copy, move, delete, rename, mkdir).
 * <p>
 * These methods show confirmation dialogs and error messages via {@link JOptionPane}.
 */
public final class FileOperations {

    private static final Logger LOGGER = LogManager.getLogger(FileOperations.class);

    private FileOperations() {
        // Utility class
    }

    // ─── Copy: Local → Remote ────────────────────────────────────────────

    /**
     * Copies selected local files/directories to the current remote directory.
     */
    public static void copyLocalToRemote(LocalFilePanel localPanel, RemoteFilePanel remotePanel,
                                          Component parent) {
        List<Path> selectedFiles = localPanel.getSelectedPaths();
        if (selectedFiles.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "No local files selected.");
            return;
        }
        String remoteDir = remotePanel.getCurrentPath();
        SftpService sftp = remotePanel.getSftpService();

        int count = 0;
        for (Path localFile : selectedFiles) {
            String remotePath = remoteDir.endsWith("/")
                    ? remoteDir + localFile.getFileName().toString()
                    : remoteDir + "/" + localFile.getFileName().toString();
            try {
                sftp.upload(localFile.toAbsolutePath().toString(), remotePath);
                count++;
            } catch (IOException e) {
                LOGGER.error("Failed to upload {}: {}", localFile, e.getMessage());
                showError(parent, "Upload failed", localFile.getFileName().toString(), e);
            }
        }
        if (count > 0) {
            remotePanel.refresh();
        }
    }

    // ─── Copy: Remote → Local ────────────────────────────────────────────

    /**
     * Copies selected remote files/directories to the current local directory.
     */
    public static void copyRemoteToLocal(RemoteFilePanel remotePanel, LocalFilePanel localPanel,
                                          Component parent) {
        List<String> selectedFiles = remotePanel.getSelectedPaths();
        if (selectedFiles.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "No remote files selected.");
            return;
        }
        Path localDir = localPanel.getCurrentPath();
        SftpService sftp = remotePanel.getSftpService();

        int count = 0;
        for (String remotePath : selectedFiles) {
            String fileName = remotePath.substring(remotePath.lastIndexOf('/') + 1);
            Path localFile = localDir.resolve(fileName);
            try {
                sftp.download(remotePath, localFile.toAbsolutePath().toString());
                count++;
            } catch (IOException e) {
                LOGGER.error("Failed to download {}: {}", remotePath, e.getMessage());
                showError(parent, "Download failed", fileName, e);
            }
        }
        if (count > 0) {
            localPanel.refresh();
        }
    }

    // ─── Delete: Local ───────────────────────────────────────────────────

    /**
     * Deletes selected local files/directories after confirmation.
     */
    public static void deleteLocal(LocalFilePanel localPanel, Component parent) {
        List<Path> selectedFiles = localPanel.getSelectedPaths();
        if (selectedFiles.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "No local files selected.");
            return;
        }

        int result = JOptionPane.showConfirmDialog(parent,
                "Delete " + selectedFiles.size() + " selected item(s)?\nThis cannot be undone.",
                "Confirm Delete",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (result != JOptionPane.YES_OPTION) {
            return;
        }

        for (Path file : selectedFiles) {
            try {
                deleteRecursively(file);
            } catch (IOException e) {
                LOGGER.error("Failed to delete {}: {}", file, e.getMessage());
                showError(parent, "Delete failed", file.getFileName().toString(), e);
            }
        }
        localPanel.refresh();
    }

    // ─── Delete: Remote ──────────────────────────────────────────────────

    /**
     * Deletes selected remote files/directories after confirmation.
     */
    public static void deleteRemote(RemoteFilePanel remotePanel, Component parent) {
        List<String> selectedFiles = remotePanel.getSelectedPaths();
        if (selectedFiles.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "No remote files selected.");
            return;
        }

        int result = JOptionPane.showConfirmDialog(parent,
                "Delete " + selectedFiles.size() + " selected remote item(s)?\nThis cannot be undone.",
                "Confirm Delete",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (result != JOptionPane.YES_OPTION) {
            return;
        }

        SftpService sftp = remotePanel.getSftpService();
        for (String remotePath : selectedFiles) {
            try {
                sftp.delete(remotePath);
            } catch (IOException e) {
                LOGGER.error("Failed to delete remote {}: {}", remotePath, e.getMessage());
                showError(parent, "Remote delete failed",
                        remotePath.substring(remotePath.lastIndexOf('/') + 1), e);
            }
        }
        remotePanel.refresh();
    }

    // ─── Rename: Local ───────────────────────────────────────────────────

    /**
     * Renames a single selected local file/directory.
     */
    public static void renameLocal(LocalFilePanel localPanel, Component parent) {
        Path selected = localPanel.getSelectedPath();
        if (selected == null) {
            JOptionPane.showMessageDialog(parent, "No local file selected.");
            return;
        }

        String newName = JOptionPane.showInputDialog(parent,
                "New name for " + selected.getFileName() + ":",
                selected.getFileName().toString());
        if (newName == null || newName.trim().isEmpty()) {
            return;
        }

        Path target = selected.getParent().resolve(newName.trim());
        try {
            Files.move(selected, target, StandardCopyOption.ATOMIC_MOVE);
            localPanel.refresh();
        } catch (IOException e) {
            LOGGER.error("Failed to rename {} to {}: {}", selected, target, e.getMessage());
            showError(parent, "Rename failed", selected.getFileName().toString(), e);
        }
    }

    // ─── Rename: Remote ──────────────────────────────────────────────────

    /**
     * Renames a single selected remote file/directory.
     */
    public static void renameRemote(RemoteFilePanel remotePanel, Component parent) {
        String selected = remotePanel.getSelectedPath();
        if (selected == null) {
            JOptionPane.showMessageDialog(parent, "No remote file selected.");
            return;
        }

        String oldName = selected.substring(selected.lastIndexOf('/') + 1);
        String newName = JOptionPane.showInputDialog(parent,
                "New name for " + oldName + ":", oldName);
        if (newName == null || newName.trim().isEmpty()) {
            return;
        }

        String newPath = selected.substring(0, selected.lastIndexOf('/') + 1) + newName.trim();
        try {
            remotePanel.getSftpService().rename(selected, newPath);
            remotePanel.refresh();
        } catch (IOException e) {
            LOGGER.error("Failed to rename remote {} to {}: {}", selected, newPath, e.getMessage());
            showError(parent, "Rename failed", oldName, e);
        }
    }

    // ─── MkDir: Local ────────────────────────────────────────────────────

    /**
     * Creates a new local directory after prompting for its name.
     */
    public static void mkdirLocal(LocalFilePanel localPanel, Component parent) {
        String name = JOptionPane.showInputDialog(parent, "New directory name:");
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        if (!localPanel.mkdir(name.trim())) {
            showError(parent, "Create directory failed", name.trim(), null);
        }
    }

    // ─── MkDir: Remote ───────────────────────────────────────────────────

    /**
     * Creates a new remote directory after prompting for its name.
     */
    public static void mkdirRemote(RemoteFilePanel remotePanel, Component parent) {
        String name = JOptionPane.showInputDialog(parent, "New remote directory name:");
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        if (!remotePanel.mkdir(name.trim())) {
            showError(parent, "Create remote directory failed", name.trim(), null);
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private static void showError(Component parent, String title, String fileName, Exception e) {
        String message = fileName;
        if (e != null && e.getMessage() != null) {
            message += "\n" + e.getMessage();
        }
        JOptionPane.showMessageDialog(parent, message, title, JOptionPane.ERROR_MESSAGE);
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                for (Path child : (Iterable<Path>) stream::iterator) {
                    deleteRecursively(child);
                }
            }
        }
        Files.delete(path);
    }
}
