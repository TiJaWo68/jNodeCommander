package de.in.jnc.connection.filetransfer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.SwingWorker;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.schmizz.sshj.sftp.OpenMode;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.SFTPClient;

/**
 * A {@link SwingWorker} that performs a file transfer (upload or download)
 * in the background, publishing {@link FileProgress} snapshots for UI updates.
 * <p>
 * Handles both COPY and MOVE semantics. For MOVE, originals are deleted
 * after successful transfer.
 */
public class FileTransferWorker extends SwingWorker<Void, FileProgress> {

    private static final Logger LOGGER = LogManager.getLogger(FileTransferWorker.class);
    private static final int CHUNK_SIZE = 64 * 1024; // 64 KB

    private final String operationId;
    private final List<TransferFile> files;
    private final boolean isMove;
    private final List<ProgressPanel> progressPanels;
    private final SftpService sftpService;
    private final TransferDirection direction;

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /**
     * Describes a single file to transfer.
     */
    public record TransferFile(
            String sourcePath,
            String targetPath,
            long size,
            String fileName) {}

    public enum TransferDirection {
        UPLOAD,
        DOWNLOAD
    }

    /**
     * Creates a new file transfer worker.
     *
     * @param files           list of files to transfer
     * @param isMove          true if originals should be deleted after transfer
     * @param direction       upload or download
     * @param sftpService     the SFTP service for remote operations
     * @param progressPanels  one or more progress panels to update (e.g. both local and remote)
     */
    public FileTransferWorker(List<TransferFile> files, boolean isMove,
                              TransferDirection direction,
                              SftpService sftpService,
                              ProgressPanel... progressPanels) {
        this.operationId = UUID.randomUUID().toString().substring(0, 8);
        this.files = List.copyOf(files);
        this.isMove = isMove;
        this.direction = direction;
        this.sftpService = sftpService;
        this.progressPanels = List.of(progressPanels);
    }

    public String getOperationId() {
        return operationId;
    }

    @Override
    protected Void doInBackground() throws Exception {
        long totalBytes = files.stream().mapToLong(TransferFile::size).sum();
        long cumulativeTransferred = 0;
        long startTime = System.nanoTime();

        for (int i = 0; i < files.size(); i++) {
            if (cancelled.get()) {
                break;
            }

            TransferFile file = files.get(i);
            long fileStartTime = System.nanoTime();
            long fileTransferred = 0;

            // Publish initial progress for this file
            publish(new FileProgress(
                    operationId, file.fileName(),
                    cumulativeTransferred, totalBytes,
                    0, file.size(),
                    i, files.size(),
                    System.nanoTime() - startTime,
                    FileProgress.State.IN_PROGRESS, null));

            try {
                switch (direction) {
                    case UPLOAD -> {
                        fileTransferred = transferWithProgress(
                                file.sourcePath, file.targetPath, file.size(),
                                cumulativeTransferred, totalBytes,
                                i, files.size(), file.fileName(),
                                startTime, true);
                    }
                    case DOWNLOAD -> {
                        fileTransferred = transferWithProgress(
                                file.sourcePath, file.targetPath, file.size(),
                                cumulativeTransferred, totalBytes,
                                i, files.size(), file.fileName(),
                                startTime, false);
                    }
                }

                cumulativeTransferred += file.size();

                // For MOVE: delete original after successful transfer
                if (isMove && !cancelled.get()) {
                    deleteOriginal(file, direction);
                }

            } catch (IOException e) {
                LOGGER.error("Transfer failed for {}: {}", file.fileName(), e.getMessage());
                long elapsed = System.nanoTime() - startTime;
                publish(FileProgress.failed(operationId,
                        file.fileName() + ": " + e.getMessage(), elapsed));
                return null;
            }
        }

        if (cancelled.get()) {
            publish(FileProgress.cancelled(operationId));
        } else {
            long elapsed = System.nanoTime() - startTime;
            publish(FileProgress.completed(operationId, elapsed));
        }

        return null;
    }

    @Override
    protected void process(List<FileProgress> chunks) {
        FileProgress latest = chunks.get(chunks.size() - 1);
        for (ProgressPanel pp : progressPanels) {
            pp.updateProgress(latest);
        }
    }

    @Override
    protected void done() {
        // If the worker was cancelled before completing normally,
        // ensure the UI reflects the cancelled state
        if (isCancelled() && !cancelled.get()) {
            cancelled.set(true);
            FileProgress cp = FileProgress.cancelled(operationId);
            for (ProgressPanel pp : progressPanels) {
                pp.updateProgress(cp);
            }
        }
    }

    /**
     * Cancels this operation gracefully.
     */
    public void cancelOperation() {
        cancelled.set(true);
        cancel(true);
    }

    // ─── Private helpers ─────────────────────────────────────────────────

    /**
     * Transfers a single file (upload or download) with chunked I/O and
     * progress reporting.
     *
     * @return total bytes transferred for this file
     */
    private long transferWithProgress(
            String sourcePath, String targetPath, long fileSize,
            long cumulativeTransferred, long totalBytes,
            int fileIndex, int totalFiles, String fileName,
            long startTimeNanos, boolean isUpload) throws IOException {

        byte[] buffer = new byte[CHUNK_SIZE];
        long transferred = 0;

        try (InputStream in = isUpload
                ? Files.newInputStream(Path.of(sourcePath))
                : sftpService.openRead(sourcePath);
             OutputStream out = isUpload
                     ? sftpService.openWrite(targetPath)
                     : Files.newOutputStream(Path.of(targetPath))) {

            int read;
            while ((read = in.read(buffer)) > 0) {
                if (cancelled.get()) {
                    return transferred;
                }
                out.write(buffer, 0, read);
                transferred += read;

                // Publish progress every ~256 KB or on last iteration
                if (transferred % (CHUNK_SIZE * 4) == 0 || transferred >= fileSize) {
                    long elapsed = System.nanoTime() - startTimeNanos;
                    publish(new FileProgress(
                            operationId, fileName,
                            cumulativeTransferred + transferred, totalBytes,
                            transferred, fileSize,
                            fileIndex, totalFiles,
                            elapsed,
                            FileProgress.State.IN_PROGRESS, null));
                }
            }
        }

        return transferred;
    }

    private void deleteOriginal(TransferFile file, TransferDirection dir) throws IOException {
        switch (dir) {
            case UPLOAD -> {
                // Local file: delete recursively if directory
                Path localPath = Path.of(file.sourcePath);
                deleteLocalRecursively(localPath);
                LOGGER.debug("Deleted local original after move: {}", localPath);
            }
            case DOWNLOAD -> {
                // Remote file
                sftpService.delete(file.sourcePath);
                LOGGER.debug("Deleted remote original after move: {}", file.sourcePath);
            }
        }
    }

    private static void deleteLocalRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                for (Path child : (Iterable<Path>) stream::iterator) {
                    deleteLocalRecursively(child);
                }
            }
        }
        Files.delete(path);
    }
}
