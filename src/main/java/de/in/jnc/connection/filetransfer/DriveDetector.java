package de.in.jnc.connection.filetransfer;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Detects available drive roots / mount points in a cross-platform way.
 * <p>
 * <b>Windows:</b> Returns all drive letters ({@code A:\, C:\, D:\, ...})
 * using {@link File#listRoots()}.<br>
 * <b>Linux:</b> Parses {@code /proc/mounts} and returns the root ({@code /})
 * plus all mount points backed by real block devices or non-pseudo
 * filesystems (e.g. {@code /home}, {@code /media/...}, {@code /mnt/...}).<br>
 * <b>macOS:</b> Falls back to {@link File#listRoots()} and also attempts
 * to parse {@code /proc/mounts} if available.
 */
public final class DriveDetector {

    private static final Logger LOGGER = LogManager.getLogger(DriveDetector.class);

    private static final String OS_NAME = System.getProperty("os.name").toLowerCase();

    /**
     * Filesystem types that are considered "real" on Linux.
     * Pseudo-filesystems (proc, sysfs, tmpfs, devtmpfs, cgroup, ...) are excluded.
     */
    private static final Set<String> REAL_FILESYSTEMS = Set.of(
            "ext2", "ext3", "ext4", "xfs", "btrfs", "zfs",
            "ntfs", "ntfs3", "vfat", "exfat", "fat", "msdos",
            "hfs", "hfsplus", "apfs",
            "fuse.sshfs", "fuse.glusterfs", "fuse.mergerfs",
            "reiserfs", "jfs", "jfs2", "ufs", "ufs2",
            "iso9660", "udf", "ecryptfs");

    /**
     * Mount path prefixes that should be filtered out as pseudo or uninteresting.
     */
    private static final Set<String> PSEUDO_PREFIXES = Set.of(
            "/proc", "/sys", "/dev", "/run", "/tmp", "/var/run",
            "/var/lock", "/var/tmp", "/snap/core");

    private DriveDetector() {
        // utility class
    }

    /**
     * Returns the list of available drive roots / mount points suitable for
     * a drive selector combo box.
     *
     * @return list of absolute path strings, never null
     */
    public static List<String> getAvailableDrives() {
        if (OS_NAME.contains("win")) {
            return getWindowsDrives();
        }
        // Linux / macOS / other Unix
        List<String> drives = new ArrayList<>();
        drives.addAll(getUnixMounts());
        // Fallback: if /proc/mounts parsing returned nothing, use File.listRoots()
        if (drives.isEmpty()) {
            for (File root : File.listRoots()) {
                drives.add(root.getAbsolutePath());
            }
        }
        return drives;
    }

    /**
     * Returns the user-friendly display name for a drive/mount path.
     * <p>
     * On Windows, {@code C:\} becomes {@code C:\} but shown as-is.<br>
     * On Linux, the display name is the basename of the mount point
     * (e.g. {@code /home} → {@code "home"}, {@code /} → {@code "/"}).
     *
     * @param mountPath the absolute path of the drive/mount
     * @return a short display name
     */
    public static String getDisplayName(String mountPath) {
        if (OS_NAME.contains("win")) {
            // e.g. "C:\"
            return mountPath;
        }
        // Unix: "/" stays "/", else use basename
        if ("/".equals(mountPath)) {
            return "/ (root)";
        }
        String basename = Paths.get(mountPath).getFileName().toString();
        // Include the mount path for disambiguation
        return basename + " (" + mountPath + ")";
    }

    /**
     * Returns Windows drive letters (e.g. {@code C:\}, {@code D:\}).
     */
    private static List<String> getWindowsDrives() {
        List<String> drives = new ArrayList<>();
        for (File root : File.listRoots()) {
            String path = root.getAbsolutePath();
            if (path.length() >= 2 && path.charAt(1) == ':') {
                drives.add(path);
            } else {
                drives.add(path);
            }
        }
        LOGGER.debug("Detected Windows drives: {}", drives);
        return drives;
    }

    /**
     * Parses {@code /proc/mounts} (Linux) or equivalent to find real mount points.
     * Returns only mount points backed by {@link #REAL_FILESYSTEMS} or mounted
     * at locations that appear to be user-accessible (not pseudo).
     */
    private static List<String> getUnixMounts() {
        Set<String> mounts = new LinkedHashSet<>();
        Path procMounts = Paths.get("/proc/mounts");

        if (!Files.isReadable(procMounts)) {
            LOGGER.debug("/proc/mounts not readable, falling back to File.listRoots()");
            return List.of();
        }

        try {
            List<String> lines = Files.readAllLines(procMounts, StandardCharsets.UTF_8);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                // Format: device mount_point fstype options dump pass
                String[] parts = line.split("\\s+");
                if (parts.length < 2) {
                    continue;
                }
                String mountPoint = parts[1];
                String fsType = parts.length >= 3 ? parts[2] : "";

                // Always include "/"
                if ("/".equals(mountPoint)) {
                    mounts.add("/");
                    continue;
                }

                // Skip pseudo-filesystem mount points
                if (isPseudoMount(mountPoint, fsType)) {
                    continue;
                }

                // Accept if it's a real filesystem type or a reasonable mount location
                if (REAL_FILESYSTEMS.contains(fsType)
                        || isUserFacingMount(mountPoint)) {
                    mounts.add(mountPoint);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to read /proc/mounts: {}", e.getMessage());
        }

        List<String> result = new ArrayList<>(mounts);
        LOGGER.debug("Detected Unix mounts: {}", result);
        return result;
    }

    /**
     * Returns true if the mount point or filesystem type indicates a pseudo
     * or otherwise uninteresting filesystem.
     */
    private static boolean isPseudoMount(String mountPoint, String fsType) {
        // Check filesystem type
        String ft = fsType.toLowerCase();
        if (ft.contains("proc") || ft.contains("sysfs")
                || ft.contains("tmpfs") || ft.contains("devtmpfs")
                || ft.contains("devpts") || ft.contains("cgroup")
                || ft.contains("pstore") || ft.contains("securityfs")
                || ft.contains("selinux") || ft.contains("autofs")
                || ft.contains("debugfs") || ft.contains("tracefs")
                || ft.contains("configfs") || ft.contains("efivarfs")
                || ft.contains("fuse.gvfs") || ft.contains("fuse.portal")
                || ft.contains("squashfs") || ft.contains("overlay")
                || ft.contains("fuse.lxcfs") || ft.contains("fuse.encfs")) {
            return true;
        }
        // Check mount path prefix
        for (String prefix : PSEUDO_PREFIXES) {
            if (mountPoint.startsWith(prefix) && mountPoint.length() > prefix.length()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the mount point is in a location typically used for
     * user-facing mounts (media, mnt, home, etc.), even if the filesystem
     * type is not in the known list.
     */
    private static boolean isUserFacingMount(String mountPoint) {
        return mountPoint.startsWith("/media/")
                || mountPoint.startsWith("/mnt/")
                || mountPoint.startsWith("/run/media/")
                || mountPoint.equals("/home")
                || mountPoint.equals("/opt")
                || mountPoint.equals("/usr/local");
    }
}
