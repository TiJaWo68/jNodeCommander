package de.in.jnc.connection.filetransfer;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.sftp.FileMode;
import net.schmizz.sshj.sftp.OpenMode;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.sftp.SFTPException;

/**
 * Wraps SSHJ's {@link SFTPClient} to provide high-level file operations
 * for the remote file panel.
 * <p>
 * All paths are absolute remote paths (e.g. {@code /home/user/file.txt}).
 */
public class SftpService implements Closeable {

    private static final Logger LOGGER = LogManager.getLogger(SftpService.class);

    private final SFTPClient sftpClient;
    private boolean connected;

    /**
     * Creates a new SftpService wrapping the given SFTPClient.
     *
     * @param sftpClient an already-connected SFTPClient
     */
    public SftpService(SFTPClient sftpClient) {
        this.sftpClient = sftpClient;
        this.connected = true;
    }

    /**
     * Lists the contents of a remote directory.
     *
     * @param remotePath absolute remote directory path
     * @return list of FileEntry objects
     * @throws IOException if listing fails
     */
    public List<FileEntry> listFiles(String remotePath) throws IOException {
        List<FileEntry> entries = new ArrayList<>();
        for (RemoteResourceInfo info : sftpClient.ls(remotePath)) {
            String name = info.getName();
            // Skip . and .. (SSHJ may or may not include them depending on server)
            if (".".equals(name) || "..".equals(name)) {
                continue;
            }
            FileAttributes attrs = info.getAttributes();
            boolean isDir = attrs.getType() == FileMode.Type.DIRECTORY;
            long size = attrs.getSize();
            long lastModified = attrs.getMtime() * 1000L; // convert to millis
            String permissions = formatPermissions(attrs);
            entries.add(new FileEntry(name, isDir, size, lastModified, permissions));
        }
        return entries;
    }

    /**
     * Uploads a local file to the remote server using local and remote paths.
     *
     * @param localPath  absolute path to the local source file
     * @param remotePath absolute destination path on remote
     * @throws IOException if upload fails
     */
    public void upload(String localPath, String remotePath) throws IOException {
        LOGGER.debug("Uploading {} to {}", localPath, remotePath);
        sftpClient.put(localPath, remotePath);
    }

    /**
     * Downloads a remote file to the local filesystem using local and remote paths.
     *
     * @param remotePath absolute remote source path
     * @param localPath  absolute path to the local destination file
     * @throws IOException if download fails
     */
    public void download(String remotePath, String localPath) throws IOException {
        LOGGER.debug("Downloading {} to {}", remotePath, localPath);
        sftpClient.get(remotePath, localPath);
    }

    /**
     * Deletes a remote file or empty directory.
     *
     * @param remotePath absolute remote path
     * @throws IOException if deletion fails
     */
    public void delete(String remotePath) throws IOException {
        LOGGER.debug("Deleting remote {}", remotePath);
        try {
            sftpClient.rm(remotePath); // try as file first
        } catch (SFTPException e) {
            // If it's a directory, try rmdir
            sftpClient.rmdir(remotePath);
        }
    }

    /**
     * Renames or moves a remote file/directory.
     *
     * @param oldPath absolute source path
     * @param newPath absolute destination path
     * @throws IOException if rename fails
     */
    public void rename(String oldPath, String newPath) throws IOException {
        LOGGER.debug("Renaming remote {} to {}", oldPath, newPath);
        sftpClient.rename(oldPath, newPath);
    }

    /**
     * Creates a remote directory.
     *
     * @param remotePath absolute path for the new directory
     * @throws IOException if creation fails
     */
    public void mkdir(String remotePath) throws IOException {
        LOGGER.debug("Creating remote directory {}", remotePath);
        sftpClient.mkdir(remotePath);
    }

    /**
     * Returns the current working directory on the remote server.
     *
     * @return absolute path string
     * @throws IOException if the operation fails
     */
    public String getDefaultDir() throws IOException {
        return sftpClient.canonicalize(".");
    }

    /**
     * Checks whether the SFTP connection is still alive.
     *
     * @return true if connected
     */
    public boolean isConnected() {
        return connected;
    }

    // ─── Stream-based I/O for progress tracking ─────────────────────────

    /**
     * Opens a remote file for reading (download) and returns an {@link InputStream}.
     * The caller is responsible for closing the stream.
     *
     * @param remotePath absolute remote path
     * @return an InputStream to read the remote file contents
     * @throws IOException if the file cannot be opened
     */
    public InputStream openRead(String remotePath) throws IOException {
        RemoteFile remoteFile = sftpClient.open(remotePath, EnumSet.of(OpenMode.READ));
        return remoteFile.new RemoteFileInputStream();
    }

    /**
     * Opens a remote file for writing (upload) and returns an {@link OutputStream}.
     * The caller is responsible for closing the stream.
     *
     * @param remotePath absolute remote path
     * @return an OutputStream to write the remote file contents
     * @throws IOException if the file cannot be opened for writing
     */
    public OutputStream openWrite(String remotePath) throws IOException {
        RemoteFile remoteFile = sftpClient.open(remotePath,
                EnumSet.of(OpenMode.CREAT, OpenMode.WRITE, OpenMode.TRUNC));
        return remoteFile.new RemoteFileOutputStream();
    }

    /**
     * Returns the size of a remote file in bytes.
     *
     * @param remotePath absolute remote path
     * @return file size in bytes
     * @throws IOException if the file cannot be accessed
     */
    public long getFileSize(String remotePath) throws IOException {
        FileAttributes attrs = sftpClient.stat(remotePath);
        return attrs.getSize();
    }

    /**
     * Checks whether a remote path exists.
     *
     * @param remotePath absolute remote path
     * @return true if the path exists
     */
    public boolean exists(String remotePath) {
        try {
            sftpClient.stat(remotePath);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void close() throws IOException {
        if (connected) {
            connected = false;
            sftpClient.close();
            LOGGER.debug("SFTP client closed");
        }
    }

    /**
     * Formats numeric Unix-style permissions to a human-readable string (e.g. "drwxr-xr-x").
     */
    private static String formatPermissions(FileAttributes attrs) {
        FileMode mode = attrs.getMode();
        if (mode == null) {
            return "";
        }
        int perms = mode.getMask() & 0xFFF;
        StringBuilder sb = new StringBuilder(10);

        // File type
        if (attrs.getType() == FileMode.Type.DIRECTORY) {
            sb.append('d');
        } else if (attrs.getType() == FileMode.Type.SYMLINK) {
            sb.append('l');
        } else {
            sb.append('-');
        }

        // Owner
        sb.append((perms & 0400) != 0 ? 'r' : '-');
        sb.append((perms & 0200) != 0 ? 'w' : '-');
        sb.append((perms & 0100) != 0 ? 'x' : '-');

        // Group
        sb.append((perms & 0040) != 0 ? 'r' : '-');
        sb.append((perms & 0020) != 0 ? 'w' : '-');
        sb.append((perms & 0010) != 0 ? 'x' : '-');

        // Others
        sb.append((perms & 0004) != 0 ? 'r' : '-');
        sb.append((perms & 0002) != 0 ? 'w' : '-');
        sb.append((perms & 0001) != 0 ? 'x' : '-');

        return sb.toString();
    }
}
