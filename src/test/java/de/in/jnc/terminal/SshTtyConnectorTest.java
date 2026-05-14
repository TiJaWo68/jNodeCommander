package de.in.jnc.terminal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.jediterm.core.util.TermSize;

@ExtendWith(MockitoExtension.class)
class SshTtyConnectorTest {

    private static final byte[] TEST_DATA = "Hello, SSH!".getBytes();

    @Mock
    private SshConnection sshConnection;

    private ByteArrayInputStream inputStream;
    private ByteArrayOutputStream outputStream;
    private SshTtyConnector connector;

    @BeforeEach
    void setUp() throws IOException {
        inputStream = new ByteArrayInputStream(TEST_DATA);
        outputStream = new ByteArrayOutputStream();

        lenient().when(sshConnection.isConnected()).thenReturn(true);
        lenient().when(sshConnection.getInputStream()).thenReturn(inputStream);
        lenient().when(sshConnection.getOutputStream()).thenReturn(outputStream);
        lenient().when(sshConnection.getUser()).thenReturn("admin");
        lenient().when(sshConnection.getHost()).thenReturn("server.example.com");

        connector = new SshTtyConnector(sshConnection);
        connector.init(null);  // Simulate real flow: init() sets connected = sshConnection.isConnected()
    }

    @Test
    void initShouldReturnTrueWhenConnected() {
        assertTrue(connector.init(null));  // Already initialized in setUp, so returns true
    }

    @Test
    void isConnectedShouldDelegateToSshConnection() {
        assertTrue(connector.isConnected());

        when(sshConnection.isConnected()).thenReturn(false);
        assertFalse(connector.isConnected());
    }

    @Test
    void closeShouldDisconnectSshConnection() {
        connector.close();

        verify(sshConnection).disconnect();
        assertFalse(connector.isConnected());
    }

    @Test
    void readShouldReturnCharacters() throws IOException {
        char[] buf = new char[32];
        int charsRead = connector.read(buf, 0, buf.length);

        assertEquals(TEST_DATA.length, charsRead);
        for (int i = 0; i < TEST_DATA.length; i++) {
            assertEquals((char) (TEST_DATA[i] & 0xFF), buf[i]);
        }
    }

    @Test
    void readWhenDisconnectedShouldReturnMinusOne() throws IOException {
        // Override isConnected after init: returns false, connector detects disconnected
        when(sshConnection.isConnected()).thenReturn(false);

        char[] buf = new char[32];
        int result = connector.read(buf, 0, buf.length);

        assertEquals(-1, result);
    }

    @Test
    void readWhenStreamReturnsMinusOneShouldReturnMinusOne() throws IOException {
        // Replace input stream with empty one so read() gets -1
        ByteArrayInputStream emptyStream = new ByteArrayInputStream(new byte[0]);
        when(sshConnection.getInputStream()).thenReturn(emptyStream);

        char[] buf = new char[32];
        int result = connector.read(buf, 0, buf.length);

        assertEquals(-1, result);
    }

    @Test
    void writeBytesShouldDelegateToOutputStream() throws IOException {
        byte[] data = "command\n".getBytes();

        connector.write(data);

        assertArrayEquals(data, outputStream.toByteArray());
    }

    @Test
    void writeStringShouldEncodeAndWrite() throws IOException {
        String command = "ls -la\n";

        connector.write(command);

        assertArrayEquals(command.getBytes(StandardCharsets.UTF_8),
                outputStream.toByteArray());
    }

    @Test
    void writeWhenDisconnectedShouldThrow() {
        when(sshConnection.isConnected()).thenReturn(false);

        assertThrows(IOException.class, () -> connector.write("test".getBytes()));
    }

    @Test
    void getNameShouldReturnUserAtHost() {
        assertEquals("admin@server.example.com", connector.getName());
    }

    @Test
    void readyShouldCheckInputStreamAvailability() throws IOException {
        assertTrue(connector.ready());

        ByteArrayInputStream emptyStream = new ByteArrayInputStream(new byte[0]);
        when(sshConnection.getInputStream()).thenReturn(emptyStream);

        assertFalse(connector.ready());
    }

    @Test
    void readyWhenDisconnectedShouldReturnFalse() throws IOException {
        when(sshConnection.isConnected()).thenReturn(false);

        assertFalse(connector.ready());
    }

    @Test
    void resizeShouldDelegateToSshConnection() {
        connector.resize(new TermSize(120, 40));

        verify(sshConnection).resizePty(120, 40);
    }

    @Test
    void resizeWithNullShouldBeIgnored() {
        connector.resize((TermSize) null);

        verify(sshConnection, never()).resizePty(anyInt(), anyInt());
    }
}
