package de.in.jnc.terminal;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.connection.channel.direct.Session.Shell;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider;
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile;
import net.schmizz.sshj.userauth.keyprovider.PuTTYKeyFile;

/**
 * Manages an SSH connection lifecycle via SSHJ.
 * <p>
 * Provides raw InputStream/OutputStream from a ShellChannel with PTY allocation
 * for use by a terminal emulator component.
 */
public class SshConnection implements Closeable {

    private static final Logger LOGGER = LogManager.getLogger(SshConnection.class);

    private static final int DEFAULT_COLUMNS = 80;
    private static final int DEFAULT_ROWS = 24;
    private static final String PTY_TERM_TYPE = "xterm-256color";

    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final String keyFilePath;

    private SSHClient sshClient;
    private Session session;
    private Shell shell;
    private InputStream shellInputStream;
    private OutputStream shellOutputStream;

    private volatile boolean connected;

    /**
     * Creates a new SSH connection configuration.
     *
     * @param host        remote hostname or IP
     * @param port        SSH port
     * @param user        login username
     * @param password    password for password-based auth (may be null if key is used)
     * @param keyFilePath path to private key file for key-based auth (may be null if password is used)
     */
    public SshConnection(String host, int port, String user, String password, String keyFilePath) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.keyFilePath = keyFilePath;
    }

    /**
     * Establishes the SSH connection and opens a shell channel with PTY.
     *
     * @throws IOException if connection, authentication, or shell creation fails
     */
    public void connect() throws IOException {
        LOGGER.info("Connecting to {}@{}:{}", user, host, port);

        sshClient = createSshClient();

        // MVP: accept all host keys (known-hosts verification can be added later)
        sshClient.addHostKeyVerifier(new PromiscuousVerifier());

        // Set timeouts
        sshClient.setConnectTimeout((int) TimeUnit.SECONDS.toMillis(10));
        sshClient.setTimeout((int) TimeUnit.SECONDS.toMillis(30));

        try {
            sshClient.connect(host, port);
            LOGGER.debug("TCP connection established to {}:{}", host, port);

            authenticate();

            session = sshClient.startSession();
            session.allocatePTY(PTY_TERM_TYPE, DEFAULT_COLUMNS, DEFAULT_ROWS, 0, 0,
                    Collections.emptyMap());
            shell = session.startShell();

            shellInputStream = shell.getInputStream();
            shellOutputStream = shell.getOutputStream();

            connected = true;
            LOGGER.info("SSH shell session established: {}@{}:{}", user, host, port);

        } catch (IOException e) {
            // Cleanup on failure
            disconnect();
            LOGGER.error("Failed to establish SSH connection to {}@{}:{}: {}", user, host, port, e.getMessage());
            throw e;
        }
    }

    private void authenticate() throws IOException {
        if (password != null && !password.isEmpty()) {
            LOGGER.debug("Authenticating with password for user {}", user);
            sshClient.authPassword(user, password);
        } else if (keyFilePath != null && !keyFilePath.isEmpty()) {
            LOGGER.debug("Authenticating with private key for user {}: {}", user, keyFilePath);
            FileKeyProvider keyProvider = resolveKeyProvider(keyFilePath);
            sshClient.authPublickey(user, keyProvider);
        } else {
            throw new IOException("No credentials provided: specify either password or private key");
        }
        LOGGER.debug("Authentication successful for user {}", user);
    }

    FileKeyProvider resolveKeyProvider(String keyFilePath) throws IOException {
        File keyFile = new File(keyFilePath);
        if (!keyFile.exists()) {
            throw new IOException("Private key file not found: " + keyFilePath);
        }
        if (!keyFile.isFile()) {
            throw new IOException("Private key path is not a file: " + keyFilePath);
        }

        String lowerPath = keyFilePath.toLowerCase();
        if (lowerPath.endsWith(".ppk")) {
            PuTTYKeyFile ppk = new PuTTYKeyFile();
            ppk.init(keyFile);
            return ppk;
        } else {
            OpenSSHKeyFile openssh = new OpenSSHKeyFile();
            openssh.init(keyFile);
            return openssh;
        }
    }

    /**
     * Returns the InputStream from the SSH shell channel.
     *
     * @return shell output stream
     * @throws IllegalStateException if not connected
     */
    public InputStream getInputStream() {
        checkConnected();
        return shellInputStream;
    }

    /**
     * Returns the OutputStream to the SSH shell channel.
     *
     * @return shell input stream
     * @throws IllegalStateException if not connected
     */
    public OutputStream getOutputStream() {
        checkConnected();
        return shellOutputStream;
    }

    /**
     * Opens a new SFTP channel on the existing SSH connection.
     * <p>
     * The returned {@link SFTPClient} can be used for file transfer operations
     * (list, upload, download, delete, rename, mkdir) while the shell session
     * remains active in parallel.
     *
     * @return a new SFTPClient instance
     * @throws IOException if the SFTP subsystem could not be opened
     * @throws IllegalStateException if not connected
     */
    public SFTPClient getSFTPClient() throws IOException {
        checkConnected();
        LOGGER.debug("Opening SFTP channel on {}", this);
        return sshClient.newSFTPClient();
    }

    /**
     * Resizes the remote PTY terminal.
     *
     * @param columns new number of columns
     * @param rows    new number of rows
     */
    public void resizePty(int columns, int rows) {
        if (!connected || shell == null) {
            return;
        }
        try {
            shell.changeWindowDimensions(columns, rows, 0, 0);
            LOGGER.debug("PTY resized to {}x{}", columns, rows);
        } catch (IOException e) {
            LOGGER.warn("Failed to resize PTY to {}x{}: {}", columns, rows, e.getMessage());
        }
    }

    /**
     * Returns whether the SSH connection is currently established.
     *
     * @return true if connected
     */
    public boolean isConnected() {
        return connected;
    }

    /**
     * Gracefully closes the SSH connection and releases all resources.
     */
    @Override
    public void close() {
        disconnect();
    }

    /**
     * Gracefully closes the SSH connection and releases all resources.
     */
    public void disconnect() {
        connected = false;

        closeQuietly(shellOutputStream);
        closeQuietly(shellInputStream);

        if (shell != null) {
            try {
                shell.close();
            } catch (IOException e) {
                LOGGER.warn("Error closing shell: {}", e.getMessage());
            }
            shell = null;
        }

        if (session != null) {
            try {
                session.close();
            } catch (IOException e) {
                LOGGER.warn("Error closing session: {}", e.getMessage());
            }
            session = null;
        }

        if (sshClient != null) {
            try {
                sshClient.disconnect();
            } catch (IOException e) {
                LOGGER.warn("Error disconnecting SSH client: {}", e.getMessage());
            }
            sshClient = null;
        }

        LOGGER.info("SSH connection closed: {}@{}:{}", user, host, port);
    }

    private void checkConnected() {
        if (!connected) {
            throw new IllegalStateException("SSH connection is not established. Call connect() first.");
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                // Ignore during cleanup
            }
        }
    }

    /**
     * Factory method to create the SSH client.
     * <p>
     * Override in tests to inject a mock/stub SSHClient.
     *
     * @return a new SSHClient instance
     */
    SSHClient createSshClient() {
        return new SSHClient();
    }

    // --- Getters for metadata ---

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getUser() {
        return user;
    }

    @Override
    public String toString() {
        return user + "@" + host + ":" + port;
    }
}
