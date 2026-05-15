package de.in.jnc.connection.filetransfer;

import java.awt.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JOptionPane;
import javax.swing.SwingWorker;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.in.jnc.connection.filetransfer.FileTransferWorker.TransferDirection;
import de.in.jnc.connection.filetransfer.FileTransferWorker.TransferFile;

/**
 * Static utility methods for file operations (copy, move, delete, rename, mkdir).
 * <p>
 * Confirmation dialogs still run on the EDT. Actual I/O is delegated to
 * {@link SwingWorker} instances so the UI stays responsive. Methods return
 * a {@link Runnable} (or worker) that the caller can execute.
 */
public final class FileOperations {

    private static final Logger LOGGER = LogManager.getLogger(FileOperations.class);

    private FileOperations() {
        // Utility class
    }

    // ─── Copy / Move: Worker helpers ────────────────────────────────────

    /**
     * Prepares a {@link FileTransferWorker} for copying files from local to remote.
     *
     * @param localPanel    the local file panel (source)
     * @param remotePanel   the remote file panel (target)
     * @param parent        parent component for dialogs
     * @param progressPanel the shared progress panel for UI updates
     * @return a worker ready to execute, or null if nothing selected or cancelled
     */
    public static FileTransferWorker prepareCopyLocalToRemote(
            LocalFilePanel localPanel, RemoteFilePanel remotePanel,
            Component parent, ProgressPanel progressPanel) {
        List<Path> selectedFiles = localPanel.getSelectedPaths();
        if (selectedFiles.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "No local files selected.");
            return null;
        }

        String remoteDir = remotePanel.getCurrentPath();
        List<TransferFile> files = buildTransferFilesLocal(selectedFiles, remoteDir);
        if (files.isEmpty()) {
            return null;
        }

        LOGGER.info("Copy {} local file(s) to remote directory {}", files.size(), remoteDir);
        return new FileTransferWorker(files, false, TransferDirection.UPLOAD,
                remotePanel.getSftpService(), progressPanel);
    }

    /**
     * Prepares a {@link FileTransferWorker} for copying files from remote to local.
     */
    public static FileTransferWorker prepareCopyRemoteToLocal(
            RemoteFilePanel remotePanel, LocalFilePanel localPanel,
            Component parent, ProgressPanel progressPanel) {
        List<String> selectedFiles = remotePanel.getSelectedPaths();
        if (selectedFiles.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "No remote files selected.");
            return null;
        }

        Path localDir = localPanel.getCurrentPath();
        SftpService sftp = remotePanel.getSftpService();
        List<TransferFile> files = buildTransferFilesRemote(selectedFiles, localDir, sftp);
        if (files.isEmpty()) {
            return null;
        }

        LOGGER.info("Copy {} remote file(s) to local directory {}", files.size(), localDir);
        return new FileTransferWorker(files, false, TransferDirection.DOWNLOAD, sftp, progressPanel);
    }

    /**
     * Prepares a {@link FileTransferWorker} for moving files from local to remote.
     */
    public static FileTransferWorker prepareMoveLocalToRemote(
            LocalFilePanel localPanel, RemoteFilePanel remotePanel,
            Component parent, ProgressPanel progressPanel) {
        List<Path> selectedFiles = localPanel.getSelectedPaths();
        if (selectedFiles.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "No local files selected.");
            return null;
        }

        int result = JOptionPane.showConfirmDialog(parent,
                "Move " + selectedFiles.size() + " selected item(s) to remote?\n"
                        + "Local originals will be deleted after upload.",
                "Confirm Move",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (result != JOptionPane.YES_OPTION) {
            return null;
        }

        String remoteDir = remotePanel.getCurrentPath();
        List<TransferFile> files = buildTransferFilesLocal(selectedFiles, remoteDir);
        if (files.isEmpty()) {
            return null;
        }

        LOGGER.info("Move {} local file(s) to remote directory {} (originals will be deleted after upload)",
                files.size(), remoteDir);
        return new FileTransferWorker(files, true, TransferDirection.UPLOAD,
                remotePanel.getSftpService(), progressPanel);
    }

    /**
     * Prepares a {@link FileTransferWorker} for moving files from remote to local.
     */
    public static FileTransferWorker prepareMoveRemoteToLocal(
            RemoteFilePanel remotePanel, LocalFilePanel localPanel,
            Component parent, ProgressPanel progressPanel) {
        List<String> selectedFiles = remotePanel.getSelectedPaths();
        if (selectedFiles.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "No remote files selected.");
            return null;
        }

        int result = JOptionPane.showConfirmDialog(parent,
                "Move " + selectedFiles.size() + " selected remote item(s) to local?\n"
                        + "Remote originals will be deleted after download.",
                "Confirm Move",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (result != JOptionPane.YES_OPTION) {
            return null;
        }

        Path localDir = localPanel.getCurrentPath();
        SftpService sftp = remotePanel.getSftpService();
        List<TransferFile> files = buildTransferFilesRemote(selectedFiles, localDir, sftp);
        if (files.isEmpty()) {
            return null;
        }

        LOGGER.info("Move {} remote file(s) to local directory {} (originals will be deleted after download)",
                files.size(), localDir);
        return new FileTransferWorker(files, true, TransferDirection.DOWNLOAD, sftp, progressPanel);
    }

    // ─── Delete ─────────────────────────────────────────────────────────

    /**
     * Creates a {@link SwingWorker} that deletes selected local files.
     *
     * @param localPanel the local file panel
     * @param parent     parent component for dialogs
     * @return a worker that performs the deletion, or null if nothing selected
     */
    public static SwingWorker<Void, Void> createDeleteLocalWorker(
            LocalFilePanel localPanel, Component parent) {
        List<Path> selectedFiles = localPanel.getSelectedPaths();
        if (selectedFiles.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "No local files selected.");
            return null;
        }

        int result = JOptionPane.showConfirmDialog(parent,
                "Delete " + selectedFiles.size() + " selected item(s)?\nThis cannot be undone.",
                "Confirm Delete",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (result != JOptionPane.YES_OPTION) {
            return null;
        }

        List<Path> filesCopy = List.copyOf(selectedFiles);
        LOGGER.info("Deleting {} local file(s): {}", filesCopy.size(), filesCopy);
        return new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                for (Path file : filesCopy) {
                    if (isCancelled()) break;
                    try {
                        deleteRecursively(file);
                        LOGGER.debug("Deleted local: {}", file);
                    } catch (IOException e) {
                        LOGGER.error("Failed to delete {}: {}", file, e.getMessage());
                    }
                }
                return null;
            }

            @Override
            protected void done() {
                localPanel.refresh();
            }
        };
    }

    /**
     * Creates a {@link SwingWorker} that deletes selected remote files.
     */
    public static SwingWorker<Void, Void> createDeleteRemoteWorker(
            RemoteFilePanel remotePanel, Component parent) {
        List<String> selectedFiles = remotePanel.getSelectedPaths();
        if (selectedFiles.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "No remote files selected.");
            return null;
        }

        int result = JOptionPane.showConfirmDialog(parent,
                "Delete " + selectedFiles.size() + " selected remote item(s)?\nThis cannot be undone.",
                "Confirm Delete",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (result != JOptionPane.YES_OPTION) {
            return null;
        }

        SftpService sftp = remotePanel.getSftpService();
        List<String> filesCopy = List.copyOf(selectedFiles);
        LOGGER.info("Deleting {} remote file(s): {}", filesCopy.size(), filesCopy);
        return new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                for (String remotePath : filesCopy) {
                    if (isCancelled()) break;
                    try {
                        sftp.delete(remotePath);
                        LOGGER.debug("Deleted remote: {}", remotePath);
                    } catch (IOException e) {
                        LOGGER.error("Failed to delete remote {}: {}", remotePath, e.getMessage());
                    }
                }
                return null;
            }

            @Override
            protected void done() {
                remotePanel.refresh();
            }
        };
    }

    // ─── Rename ─────────────────────────────────────────────────────────

    /**
     * Creates a {@link SwingWorker} that renames a local file.
     *
     * @param localPanel the local file panel
     * @param parent     parent component for dialogs
     * @return a worker that performs the rename, or null
     */
    public static SwingWorker<Void, Void> createRenameLocalWorker(
            LocalFilePanel localPanel, Component parent) {
        Path selected = localPanel.getSelectedPath();
        if (selected == null) {
            JOptionPane.showMessageDialog(parent, "No local file selected.");
            return null;
        }

        String newName = JOptionPane.showInputDialog(parent,
                "New name for " + selected.getFileName() + ":",
                selected.getFileName().toString());
        if (newName == null || newName.trim().isEmpty()) {
            return null;
        }

        Path target = selected.getParent().resolve(newName.trim());
        LOGGER.info("Renaming local {} -> {}", selected, target);
        return new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                Files.move(selected, target, StandardCopyOption.ATOMIC_MOVE);
                LOGGER.debug("Local rename done: {} -> {}", selected, target);
                return null;
            }

            @Override
            protected void done() {
                localPanel.refresh();
            }
        };
    }

    /**
     * Creates a {@link SwingWorker} that renames a remote file.
     */
    public static SwingWorker<Void, Void> createRenameRemoteWorker(
            RemoteFilePanel remotePanel, Component parent) {
        String selected = remotePanel.getSelectedPath();
        if (selected == null) {
            JOptionPane.showMessageDialog(parent, "No remote file selected.");
            return null;
        }

        String oldName = selected.substring(selected.lastIndexOf('/') + 1);
        String newName = JOptionPane.showInputDialog(parent,
                "New name for " + oldName + ":", oldName);
        if (newName == null || newName.trim().isEmpty()) {
            return null;
        }

        String newPath = selected.substring(0, selected.lastIndexOf('/') + 1) + newName.trim();
        SftpService sftp = remotePanel.getSftpService();
        LOGGER.info("Renaming remote {} -> {}", selected, newPath);
        return new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                sftp.rename(selected, newPath);
                LOGGER.debug("Remote rename done: {} -> {}", selected, newPath);
                return null;
            }

            @Override
            protected void done() {
                remotePanel.refresh();
            }
        };
    }

    // ─── MkDir ──────────────────────────────────────────────────────────

    /**
     * Creates a {@link SwingWorker} that creates a local directory.
     */
    public static SwingWorker<Void, Void> createMkdirLocalWorker(
            LocalFilePanel localPanel, Component parent) {
        String name = JOptionPane.showInputDialog(parent, "New directory name:");
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        String dirName = name.trim();
        LOGGER.info("Creating local directory: {} in {}", dirName, localPanel.getCurrentPath());
        return new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                if (!localPanel.mkdir(dirName)) {
                    throw new IOException("Failed to create directory: " + dirName);
                }
                LOGGER.debug("Local directory created: {} in {}", dirName, localPanel.getCurrentPath());
                return null;
            }
        };
    }

    /**
     * Creates a {@link SwingWorker} that creates a remote directory.
     */
    public static SwingWorker<Void, Void> createMkdirRemoteWorker(
            RemoteFilePanel remotePanel, Component parent) {
        String name = JOptionPane.showInputDialog(parent, "New remote directory name:");
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        String dirName = name.trim();
        LOGGER.info("Creating remote directory: {} in {}", dirName, remotePanel.getCurrentPath());
        return new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                if (!remotePanel.mkdir(dirName)) {
                    throw new IOException("Failed to create remote directory: " + dirName);
                }
                LOGGER.debug("Remote directory created: {} in {}", dirName, remotePanel.getCurrentPath());
                return null;
            }
        };
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    /**
     * Builds a list of {@link TransferFile} from local selected paths.
     */
    private static List<TransferFile> buildTransferFilesLocal(
            List<Path> selectedFiles, String remoteDir) {
        List<TransferFile> files = new ArrayList<>();
        for (Path localFile : selectedFiles) {
            String remotePath = remoteDir.endsWith("/")
                    ? remoteDir + localFile.getFileName().toString()
                    : remoteDir + "/" + localFile.getFileName().toString();
            long size = localFile.toFile().length();
            files.add(new TransferFile(
                    localFile.toAbsolutePath().toString(),
                    remotePath,
                    size,
                    localFile.getFileName().toString()));
        }
        return files;
    }

    /**
     * Builds a list of {@link TransferFile} from remote selected paths.
     * Attempts to resolve remote file sizes via SFTP.
     */
    private static List<TransferFile> buildTransferFilesRemote(
            List<String> selectedFiles, Path localDir, SftpService sftp) {
        List<TransferFile> files = new ArrayList<>();
        for (String remotePath : selectedFiles) {
            String fileName = remotePath.substring(remotePath.lastIndexOf('/') + 1);
            Path localFile = localDir.resolve(fileName);
            long size = 0;
            try {
                size = sftp.getFileSize(remotePath);
            } catch (IOException e) {
                LOGGER.warn("Cannot determine size of remote {}: {}", remotePath, e.getMessage());
            }
            files.add(new TransferFile(
                    remotePath,
                    localFile.toAbsolutePath().toString(),
                    size,
                    fileName));
        }
        return files;
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
