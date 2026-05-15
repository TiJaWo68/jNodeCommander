package de.in.jnc.connection.filetransfer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A panel that displays a vertical stack of progress rows, one per active
 * file transfer operation. Each row shows the filename, a progress bar,
 * transfer speed, and file count (e.g. "3/12").
 * <p>
 * Rows for completed or failed operations auto-remove after a short delay.
 */
public class ProgressPanel extends JPanel {

    private static final Logger LOGGER = LogManager.getLogger(ProgressPanel.class);

    private static final int AUTO_REMOVE_DELAY_MS = 3000;
    private static final int HEIGHT_PER_ROW = 36;
    private static final int MAX_VISIBLE_ROWS = 5;

    private final Map<String, ProgressRow> rows = new ConcurrentHashMap<>();
    private int visibleCount = 0;

    /**
     * Creates an empty progress panel.
     */
    public ProgressPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        setVisible(false);
    }

    /**
     * Adds or updates a progress row for the given operation.
     *
     * @param progress the progress snapshot
     */
    public void updateProgress(FileProgress progress) {
        SwingUtilities.invokeLater(() -> {
            String opId = progress.operationId();
            ProgressRow row = rows.get(opId);

            if (row == null) {
                // New operation
                row = new ProgressRow();
                rows.put(opId, row);
                add(row);
                visibleCount++;
                updateVisibility();
            }

            row.update(progress);

            // If completed/failed/cancelled, schedule removal
            if (progress.state() == FileProgress.State.COMPLETED
                    || progress.state() == FileProgress.State.FAILED
                    || progress.state() == FileProgress.State.CANCELLED) {
                Timer timer = new Timer(AUTO_REMOVE_DELAY_MS, e -> {
                    SwingUtilities.invokeLater(() -> {
                        ProgressRow removed = rows.remove(opId);
                        if (removed != null) {
                            remove(removed);
                            visibleCount--;
                            updateVisibility();
                            revalidate();
                            repaint();
                        }
                    });
                });
                timer.setRepeats(false);
                timer.start();
            }

            revalidate();
            repaint();
        });
    }

    /**
     * Removes a progress row immediately (e.g. on cancel).
     *
     * @param operationId the operation to remove
     */
    public void removeProgress(String operationId) {
        SwingUtilities.invokeLater(() -> {
            ProgressRow removed = rows.remove(operationId);
            if (removed != null) {
                remove(removed);
                visibleCount--;
                updateVisibility();
                revalidate();
                repaint();
            }
        });
    }

    /**
     * Clears all completed/failed rows immediately.
     */
    public void clearCompleted() {
        SwingUtilities.invokeLater(() -> {
            rows.entrySet().removeIf(entry -> {
                ProgressRow row = entry.getValue();
                if (row.isTerminal()) {
                    remove(row);
                    visibleCount--;
                    return true;
                }
                return false;
            });
            updateVisibility();
            revalidate();
            repaint();
        });
    }

    /**
     * Returns the number of currently active (in-progress) operations.
     */
    public int activeCount() {
        return (int) rows.values().stream().filter(r -> !r.isTerminal()).count();
    }

    private void updateVisibility() {
        boolean hasContent = visibleCount > 0;
        if (hasContent != isVisible()) {
            setVisible(hasContent);
        }
        // Adjust preferred height
        int rowCount = Math.min(visibleCount, MAX_VISIBLE_ROWS);
        setPreferredSize(new Dimension(0, rowCount * HEIGHT_PER_ROW + 4));
    }

    // ─── Inner row component ────────────────────────────────────────────

    /**
     * A single row in the progress panel, containing a filename label,
     * progress bar, speed label, and file counter.
     */
    private static class ProgressRow extends JPanel {

        private static final Color COLOR_COMPLETED = new Color(0x2E, 0x7D, 0x32); // green
        private static final Color COLOR_FAILED = new Color(0xC6, 0x28, 0x28);    // red

        private final JLabel fileLabel;
        private final JProgressBar progressBar;
        private final JLabel infoLabel;

        private boolean terminal;

        ProgressRow() {
            setLayout(new BorderLayout(6, 0));
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY),
                    BorderFactory.createEmptyBorder(2, 4, 2, 4)));

            fileLabel = new JLabel();
            fileLabel.setPreferredSize(new Dimension(180, 20));
            fileLabel.setMinimumSize(new Dimension(80, 20));

            progressBar = new JProgressBar(0, 100);
            progressBar.setStringPainted(true);
            progressBar.setPreferredSize(new Dimension(100, 20));

            infoLabel = new JLabel();
            infoLabel.setPreferredSize(new Dimension(120, 20));

            add(fileLabel, BorderLayout.WEST);
            add(progressBar, BorderLayout.CENTER);
            add(infoLabel, BorderLayout.EAST);

            setPreferredSize(new Dimension(0, HEIGHT_PER_ROW));
        }

        void update(FileProgress progress) {
            switch (progress.state()) {
                case IN_PROGRESS -> {
                    terminal = false;
                    // Update file label: show current file with file counter
                    String fileInfo = progress.totalFiles() > 1
                            ? String.format("(%d/%d) %s", progress.fileIndex() + 1, progress.totalFiles(), progress.fileName())
                            : progress.fileName();
                    fileLabel.setText(truncate(fileInfo, 30));
                    fileLabel.setForeground(Color.BLACK);

                    // Update progress bar
                    int percent = Math.round(progress.overallProgress() * 100f);
                    progressBar.setValue(percent);
                    progressBar.setString(percent + "%");

                    // Update info: speed and file progress
                    String speed = progress.formattedSpeed();
                    String fileProgress = progress.fileTotalBytes() > 0
                            ? String.format(" %s/%s",
                                    formatSize(progress.fileBytesTransferred()),
                                    formatSize(progress.fileTotalBytes()))
                            : "";
                    infoLabel.setText(speed + fileProgress);
                    infoLabel.setForeground(Color.DARK_GRAY);
                }
                case COMPLETED -> {
                    terminal = true;
                    fileLabel.setText("\u2713 Complete");
                    fileLabel.setForeground(COLOR_COMPLETED);
                    progressBar.setValue(100);
                    progressBar.setString("100%");
                    String speed = progress.formattedSpeed();
                    infoLabel.setText(speed.isEmpty() ? "" : "Avg: " + speed);
                    infoLabel.setForeground(COLOR_COMPLETED);
                }
                case FAILED -> {
                    terminal = true;
                    String errMsg = progress.errorMessage() != null ? progress.errorMessage() : "Error";
                    fileLabel.setText("\u2717 " + truncate(errMsg, 35));
                    fileLabel.setForeground(COLOR_FAILED);
                    progressBar.setValue(0);
                    progressBar.setString("Failed");
                    infoLabel.setText("");
                }
                case CANCELLED -> {
                    terminal = true;
                    fileLabel.setText("Cancelled");
                    fileLabel.setForeground(Color.GRAY);
                    progressBar.setValue(0);
                    progressBar.setString("Cancelled");
                    infoLabel.setText("");
                }
            }
        }

        boolean isTerminal() {
            return terminal;
        }

        private static String truncate(String text, int maxLen) {
            if (text == null || text.length() <= maxLen) {
                return text;
            }
            return text.substring(0, maxLen - 3) + "...";
        }

        private static String formatSize(long bytes) {
            final String[] units = { "B", "KB", "MB", "GB" };
            double value = bytes;
            int unitIndex = 0;
            while (value >= 1024 && unitIndex < units.length - 1) {
                value /= 1024;
                unitIndex++;
            }
            if (unitIndex == 0) {
                return String.format("%d %s", (int) value, units[unitIndex]);
            }
            return String.format("%.1f %s", value, units[unitIndex]);
        }
    }
}
