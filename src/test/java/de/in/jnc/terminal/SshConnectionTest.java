package de.in.jnc.terminal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.ConnectionException;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.connection.channel.direct.Session.Shell;
import net.schmizz.sshj.transport.TransportException;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;

@ExtendWith(MockitoExtension.class)
class SshConnectionTest {

    private static final String TEST_HOST = "test.example.com";
    private static final int TEST_PORT = 2222;
    private static final String TEST_USER = "testuser";
    private static final String TEST_PASSWORD = "secret123";

    @Mock
    private SSHClient sshClient;
    @Mock
    private Session session;
    @Mock
    private Shell shell;
    @Mock
    private FileKeyProvider keyProvider;

    private ByteArrayInputStream shellInputStream;
    private ByteArrayOutputStream shellOutputStream;

    @BeforeEach
    void setUp() throws Exception {
        shellInputStream = new ByteArrayInputStream("test data".getBytes());
        shellOutputStream = new ByteArrayOutputStream();

        lenient().when(sshClient.startSession()).thenReturn(session);
        lenient().when(session.startShell()).thenReturn(shell);
        lenient().when(shell.getInputStream()).thenReturn(shellInputStream);
        lenient().when(shell.getOutputStream()).thenReturn(shellOutputStream);
    }

    @Test
    void connectWithPasswordShouldEstablishSession() throws IOException {
        SshConnection connection = createTestConnection(
                TEST_HOST, TEST_PORT, TEST_USER, TEST_PASSWORD, null);

        connection.connect();

        verify(sshClient).addHostKeyVerifier(any(HostKeyVerifier.class));
        verify(sshClient).connect(TEST_HOST, TEST_PORT);
        verify(sshClient).authPassword(TEST_USER, TEST_PASSWORD);
        verify(session).allocatePTY(eq("xterm-256color"), eq(80), eq(24), eq(0), eq(0),
                eq(Collections.emptyMap()));
        verify(session).startShell();
        assertTrue(connection.isConnected());
        assertNotNull(connection.getInputStream());
        assertNotNull(connection.getOutputStream());
    }

    @Test
    void connectWithKeyFileShouldUsePublicKeyAuth() throws IOException {
        SshConnection connection = new SshConnection(
                "host", 22, "user", null, "/path/to/fake-key.pem") {
            @Override
            SSHClient createSshClient() {
                return sshClient;
            }

            @Override
            FileKeyProvider resolveKeyProvider(String keyFilePath) throws IOException {
                assertEquals("/path/to/fake-key.pem", keyFilePath);
                return keyProvider;
            }
        };

        connection.connect();

        verify(sshClient).authPublickey(eq("user"), eq(keyProvider));
    }

    @Test
    void connectWithNoCredentialsShouldThrow() {
        SshConnection connection = createTestConnection(
                TEST_HOST, TEST_PORT, TEST_USER, null, null);

        assertThrows(IOException.class, () -> connection.connect());
    }

    @Test
    void disconnectShouldCloseResources() throws IOException {
        SshConnection connection = createTestConnection(
                TEST_HOST, TEST_PORT, TEST_USER, TEST_PASSWORD, null);

        connection.connect();
        connection.disconnect();

        assertFalse(connection.isConnected());
        verify(shell).close();
        verify(session).close();
        verify(sshClient).disconnect();
    }

    @Test
    void resizePtyShouldChangeWindowDimensions() throws IOException {
        SshConnection connection = createTestConnection(
                TEST_HOST, TEST_PORT, TEST_USER, TEST_PASSWORD, null);

        connection.connect();
        connection.resizePty(120, 40);

        verify(shell).changeWindowDimensions(120, 40, 0, 0);
    }

    @Test
    void getInputStreamShouldThrowWhenNotConnected() {
        SshConnection connection = createTestConnection(
                TEST_HOST, TEST_PORT, TEST_USER, TEST_PASSWORD, null);

        assertThrows(IllegalStateException.class, () -> connection.getInputStream());
    }

    @Test
    void getOutputStreamShouldThrowWhenNotConnected() {
        SshConnection connection = createTestConnection(
                TEST_HOST, TEST_PORT, TEST_USER, TEST_PASSWORD, null);

        assertThrows(IllegalStateException.class, () -> connection.getOutputStream());
    }

    @Test
    void connectFailureShouldDisconnect() throws IOException {
        when(sshClient.startSession()).thenThrow(
                new ConnectionException("Connection refused"));

        SshConnection connection = createTestConnection(
                TEST_HOST, TEST_PORT, TEST_USER, TEST_PASSWORD, null);

        assertThrows(IOException.class, () -> connection.connect());
        assertFalse(connection.isConnected());
        verify(sshClient).disconnect();
    }

    @Test
    void gettersShouldReturnConfiguredValues() {
        SshConnection connection = createTestConnection(
                TEST_HOST, TEST_PORT, TEST_USER, TEST_PASSWORD, null);

        assertEquals(TEST_HOST, connection.getHost());
        assertEquals(TEST_PORT, connection.getPort());
        assertEquals(TEST_USER, connection.getUser());
    }

    @Test
    void toStringShouldFormatUserAtHostPort() {
        SshConnection connection = createTestConnection(
                TEST_HOST, TEST_PORT, TEST_USER, TEST_PASSWORD, null);

        assertEquals("testuser@test.example.com:2222", connection.toString());
    }

    /**
     * Creates an SshConnection that uses the mock SSHClient, Session, and Shell
     * via the overridable {@code createSshClient()} factory method.
     */
    private SshConnection createTestConnection(
            String host, int port, String user, String password, String keyFile) {
        return new SshConnection(host, port, user, password, keyFile) {
            @Override
            SSHClient createSshClient() {
                return sshClient;
            }
        };
    }
}
