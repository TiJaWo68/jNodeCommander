package de.in.jnc.connection.filetransfer;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFileAttributes;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Represents a single file or directory entry in either the local or remote file panel.
 * <p>
 * This is a simple immutable data class used by {@link FileTableModel} and both panels.
 */
public class FileEntry {

    private final String name;
    private final boolean directory;
    private final long size;
    private final long lastModified;
    private final String permissions;

    /**
     * Creates a new file entry.
     *
     * @param name         file or directory name
     * @param isDirectory  true if this is a directory
     * @param size         file size in bytes
     * @param lastModified last modified timestamp (millis since epoch)
     * @param permissions  human-readable permission string (e.g. "drwxr-xr-x" or "rw-rw-rw-")
     */
    public FileEntry(String name, boolean isDirectory, long size, long lastModified, String permissions) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.directory = isDirectory;
        this.size = size;
        this.lastModified = lastModified;
        this.permissions = permissions != null ? permissions : "";
    }

    /**
     * Factory method to create a FileEntry from a local {@link Path} and its attributes.
     *
     * @param path      the local file path
     * @param attrs     the file attributes (from {@link java.nio.file.Files#readAttributes})
     * @return a new FileEntry
     */
    public static FileEntry fromLocalPath(Path path, BasicFileAttributes attrs) {
        String name = path.getFileName() != null ? path.getFileName().toString() : path.toString();
        boolean isDir = attrs.isDirectory();
        long size = attrs.size();
        long lastModified = attrs.lastModifiedTime().toMillis();
        String perms = formatLocalPermissions(path);
        return new FileEntry(name, isDir, size, lastModified, perms);
    }

    /**
     * Formats a human-readable permissions string from a Path.
     * On Windows, this returns "rw-rw-rw-" (all accessible).
     * On POSIX systems, it reads actual file permissions.
     */
    private static String formatLocalPermissions(Path path) {
        try {
            Set<PosixFilePermission> posixPerms = 
                java.nio.file.Files.getPosixFilePermissions(path);
            return formatPosixPermissions(posixPerms, 
                java.nio.file.Files.readAttributes(path, PosixFileAttributes.class).isDirectory());
        } catch (Exception e) {
            // Fallback for Windows or non-POSIX filesystems
            boolean isDir = java.nio.file.Files.isDirectory(path);
            return (isDir ? "d" : "-") + "rw-rw-rw-";
        }
    }

    private static String formatPosixPermissions(Set<PosixFilePermission> perms, boolean isDir) {
        StringBuilder sb = new StringBuilder(10);
        sb.append(isDir ? 'd' : '-');
        sb.append(perms.contains(PosixFilePermission.OWNER_READ) ? 'r' : '-');
        sb.append(perms.contains(PosixFilePermission.OWNER_WRITE) ? 'w' : '-');
        sb.append(perms.contains(PosixFilePermission.OWNER_EXECUTE) ? 'x' : '-');
        sb.append(perms.contains(PosixFilePermission.GROUP_READ) ? 'r' : '-');
        sb.append(perms.contains(PosixFilePermission.GROUP_WRITE) ? 'w' : '-');
        sb.append(perms.contains(PosixFilePermission.GROUP_EXECUTE) ? 'x' : '-');
        sb.append(perms.contains(PosixFilePermission.OTHERS_READ) ? 'r' : '-');
        sb.append(perms.contains(PosixFilePermission.OTHERS_WRITE) ? 'w' : '-');
        sb.append(perms.contains(PosixFilePermission.OTHERS_EXECUTE) ? 'x' : '-');
        return sb.toString();
    }

    // --- Getters ---

    public String getName() {
        return name;
    }

    public boolean isDirectory() {
        return directory;
    }

    public long getSize() {
        return size;
    }

    public long getLastModified() {
        return lastModified;
    }

    public String getPermissions() {
        return permissions;
    }

    /**
     * Returns a human-readable size string (e.g. "2.3 KB", "14.2 MB").
     */
    public String getFormattedSize() {
        if (directory) {
            return "<DIR>";
        }
        final String[] units = { "B", "KB", "MB", "GB", "TB" };
        double len = size;
        int unitIndex = 0;
        while (len >= 1024 && unitIndex < units.length - 1) {
            len /= 1024;
            unitIndex++;
        }
        if (unitIndex == 0) {
            return String.format(Locale.ENGLISH, "%d %s", (int) len, units[unitIndex]);
        }
        return String.format(Locale.ENGLISH, "%.1f %s", len, units[unitIndex]);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FileEntry fileEntry = (FileEntry) o;
        return directory == fileEntry.directory
                && size == fileEntry.size
                && lastModified == fileEntry.lastModified
                && name.equals(fileEntry.name)
                && permissions.equals(fileEntry.permissions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, directory, size, lastModified, permissions);
    }

    @Override
    public String toString() {
        return name;
    }
}
