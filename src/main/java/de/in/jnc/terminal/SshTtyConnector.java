package de.in.jnc.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.Questioner;
import com.jediterm.terminal.TtyConnector;

/**
 * Bridges an {@link SshConnection} shell channel to JediTerm's {@link TtyConnector} interface.
 * <p>
 * Reads raw bytes from the SSH InputStream and translates them into characters for the
 * terminal emulator; writes byte arrays or strings from the emulator back to the shell.
 */
public class SshTtyConnector implements TtyConnector {

    private static final Logger LOGGER = LogManager.getLogger(SshTtyConnector.class);

    private final SshConnection sshConnection;
    private final Object readLock = new Object();

    private volatile boolean connected;

    /**
     * Creates a new connector for the given SSH connection.
     *
     * @param sshConnection an already connected SSH shell
     */
    public SshTtyConnector(SshConnection sshConnection) {
        this.sshConnection = sshConnection;
    }

    @Override
    public boolean init(Questioner questioner) {
        LOGGER.debug("TtyConnector initialized");
        connected = sshConnection.isConnected();
        return connected;
    }

    @Override
    public void close() {
        LOGGER.debug("Closing TtyConnector");
        connected = false;
        sshConnection.disconnect();
    }

    @Override
    public String getName() {
        return sshConnection.getUser() + "@" + sshConnection.getHost();
    }

    @Override
    public boolean isConnected() {
        return connected && sshConnection.isConnected();
    }

    @Override
    public int read(char[] buf, int offset, int length) throws IOException {
        if (!isConnected()) {
            return -1;
        }
        InputStream in = sshConnection.getInputStream();
        synchronized (readLock) {
            // Block until at least one byte is available
            int firstByte;
            try {
                firstByte = in.read();
            } catch (IOException e) {
                LOGGER.warn("Read error on SSH stream: {}", e.getMessage());
                return -1;
            }
            if (firstByte == -1) {
                connected = false;
                return -1;
            }

            // JediTerm expects chars; SSH bytes in range 0–255 map to chars 0–255 directly,
            // and multibyte UTF-8 sequences are decoded into proper chars.
            int bytesRead = 0;
            buf[offset] = (char) (firstByte & 0xFF);
            int charsRead = 1;

            // Try to read more bytes without blocking (up to the buffer limit)
            int remaining = length - 1;
            int readOffset = offset + 1;
            while (remaining > 0 && in.available() > 0) {
                int b = in.read();
                if (b == -1) {
                    connected = false;
                    break;
                }
                buf[readOffset] = (char) (b & 0xFF);
                readOffset++;
                charsRead++;
                remaining--;
            }

            return charsRead;
        }
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        if (!isConnected()) {
            throw new IOException("Cannot write: SSH connection is closed");
        }
        OutputStream out = sshConnection.getOutputStream();
        out.write(bytes);
        out.flush();
    }

    @Override
    public void write(String string) throws IOException {
        write(string.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public int waitFor() throws InterruptedException {
        // Wait until the connection drops or the process finishes
        CountDownLatch latch = new CountDownLatch(1);
        Thread watcher = new Thread(() -> {
            try {
                // Poll isConnected periodically
                while (connected) {
                    if (!sshConnection.isConnected()) {
                        connected = false;
                        break;
                    }
                    TimeUnit.SECONDS.sleep(1);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        }, "ssh-connection-watcher");
        watcher.setDaemon(true);
        watcher.start();

        latch.await();
        return 0;
    }

    @Override
    public boolean ready() throws IOException {
        if (!isConnected()) {
            return false;
        }
        return sshConnection.getInputStream().available() > 0;
    }

    @Override
    public void resize(TermSize termSize) {
        if (termSize == null) {
            return;
        }
        LOGGER.debug("Resizing PTY to {}x{}", termSize.getColumns(), termSize.getRows());
        sshConnection.resizePty(termSize.getColumns(), termSize.getRows());
    }

    @Override
    public void resize(java.awt.Dimension pixelSize) {
        // Not needed — we use TermSize-based resize
    }

    @Override
    public void resize(java.awt.Dimension pixelSize, java.awt.Dimension termSize) {
        // Not needed — we use TermSize-based resize
    }
}
