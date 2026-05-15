package de.in.jnc.connection.filetransfer;

/**
 * Immutable snapshot of progress for a single file transfer operation.
 * Published by {@link FileTransferWorker} and consumed by {@link ProgressPanel}.
 *
 * @param operationId   unique identifier for this transfer operation
 * @param fileName      current file being transferred
 * @param bytesTransferred  cumulative bytes transferred so far (across all files in this operation)
 * @param totalBytes    total bytes to transfer (across all files in this operation)
 * @param fileBytesTransferred  bytes transferred for the current file only
 * @param fileTotalBytes        total bytes of the current file
 * @param fileIndex     index of the current file (0-based)
 * @param totalFiles    total number of files in this operation
 * @param elapsedNanos  nanoseconds elapsed since the operation started
 * @param state         current state of the operation
 */
public record FileProgress(
        String operationId,
        String fileName,
        long bytesTransferred,
        long totalBytes,
        long fileBytesTransferred,
        long fileTotalBytes,
        int fileIndex,
        int totalFiles,
        long elapsedNanos,
        State state,
        String errorMessage) {

    public enum State {
        /** Transfer is in progress. */
        IN_PROGRESS,
        /** Transfer completed successfully. */
        COMPLETED,
        /** Transfer failed. */
        FAILED,
        /** Transfer was cancelled. */
        CANCELLED
    }

    /**
     * Returns the transfer speed in bytes per second, based on
     * {@code bytesTransferred} and {@code elapsedNanos}.
     *
     * @return speed in bytes/second, or 0 if no elapsed time
     */
    public double speedBytesPerSecond() {
        if (elapsedNanos <= 0) {
            return 0;
        }
        double seconds = elapsedNanos / 1_000_000_000.0;
        if (seconds <= 0) {
            return 0;
        }
        return bytesTransferred / seconds;
    }

    /**
     * Returns a human-readable speed string, e.g. "2.3 MB/s".
     *
     * @return formatted speed
     */
    public String formattedSpeed() {
        double speed = speedBytesPerSecond();
        if (speed <= 0) {
            return "";
        }
        final String[] units = { "B/s", "KB/s", "MB/s", "GB/s" };
        double value = speed;
        int unitIndex = 0;
        while (value >= 1024 && unitIndex < units.length - 1) {
            value /= 1024;
            unitIndex++;
        }
        if (unitIndex == 0) {
            return String.format("%.0f %s", value, units[unitIndex]);
        }
        return String.format("%.1f %s", value, units[unitIndex]);
    }

    /**
     * Returns the overall progress as a float between 0.0 and 1.0.
     */
    public float overallProgress() {
        if (totalBytes <= 0) {
            return state == State.COMPLETED ? 1f : 0f;
        }
        return Math.min(1f, (float) bytesTransferred / (float) totalBytes);
    }

    /**
     * Creates a "completed" snapshot for the given operation.
     */
    public static FileProgress completed(String operationId, long elapsedNanos) {
        return new FileProgress(operationId, "", 0, 0, 0, 0, 0, 0, elapsedNanos, State.COMPLETED, null);
    }

    /**
     * Creates a "failed" snapshot for the given operation.
     */
    public static FileProgress failed(String operationId, String errorMessage, long elapsedNanos) {
        return new FileProgress(operationId, "", 0, 0, 0, 0, 0, 0, elapsedNanos, State.FAILED, errorMessage);
    }

    /**
     * Creates a "cancelled" snapshot.
     */
    public static FileProgress cancelled(String operationId) {
        return new FileProgress(operationId, "", 0, 0, 0, 0, 0, 0, 0, State.CANCELLED, null);
    }
}
