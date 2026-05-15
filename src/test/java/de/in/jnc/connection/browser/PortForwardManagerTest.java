package de.in.jnc.connection.browser;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.net.ServerSocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import de.in.jnc.terminal.SshConnection;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder;
import net.schmizz.sshj.connection.channel.direct.Parameters;

@ExtendWith(MockitoExtension.class)
class PortForwardManagerTest {

    @Mock
    private SshConnection sshConnection;

    @Mock
    private SSHClient sshClient;

    @Mock
    private LocalPortForwarder forwarder;

    @Mock
    private ServerSocket serverSocket;

    private PortForwardManager manager;

    @BeforeEach
    void setUp() throws Exception {
        when(sshConnection.getSshClient()).thenReturn(sshClient);

        // Override createServerSocket to return a mock, avoiding
        // real port binding during tests
        manager = new PortForwardManager(sshConnection) {
            @Override
            ServerSocket createServerSocket(int port) throws IOException {
                return serverSocket;
            }
        };
    }

    @Test
    void startTunnelCreatesForwarder() throws Exception {
        when(sshConnection.executeCommand(anyString())).thenReturn("12345");
        when(sshClient.newLocalPortForwarder(any(Parameters.class), any(ServerSocket.class)))
                .thenReturn(forwarder);

        int port = manager.startTunnel("ns1/my-svc", "ns1", "my-svc", 8080);

        assertTrue(port >= 49000 && port < 49100, "Port should be in tunnel range");
        verify(sshConnection).executeCommand(contains("kubectl port-forward"));
        verify(sshConnection).executeCommand(contains("my-svc"));
        verify(sshConnection).executeCommand(contains("8080"));
        verify(sshClient).newLocalPortForwarder(any(Parameters.class), same(serverSocket));
    }

    @Test
    void startTunnelFallsBackToSudoOnFailure() throws Exception {
        when(sshConnection.executeCommand(anyString()))
                .thenThrow(new IOException("permission denied"));
        when(sshConnection.executeCommand(anyString(), eq(true)))
                .thenReturn("12345");
        when(sshClient.newLocalPortForwarder(any(Parameters.class), any(ServerSocket.class)))
                .thenReturn(forwarder);

        int port = manager.startTunnel("ns/svc", "ns", "svc", 80);

        assertTrue(port >= 49000 && port < 49100);
        verify(sshConnection).executeCommand(anyString()); // without sudo
        verify(sshConnection).executeCommand(anyString(), eq(true)); // fallback with sudo
    }

    @Test
    void startTunnelReusesExistingTunnel() throws Exception {
        when(sshConnection.executeCommand(anyString())).thenReturn("12345");
        when(sshClient.newLocalPortForwarder(any(Parameters.class), any(ServerSocket.class)))
                .thenReturn(forwarder);
        when(forwarder.isRunning()).thenReturn(true);

        int firstPort = manager.startTunnel("ns1/my-svc", "ns1", "my-svc", 8080);
        int secondPort = manager.startTunnel("ns1/my-svc", "ns1", "my-svc", 8080);

        assertEquals(firstPort, secondPort, "Should return same port for existing tunnel");
        verify(sshConnection, times(1)).executeCommand(anyString());
        verify(sshClient, times(1))
                .newLocalPortForwarder(any(Parameters.class), any(ServerSocket.class));
    }

    @Test
    void stopTunnelClosesForwarderAndKillsRemoteProcess() throws Exception {
        when(sshConnection.executeCommand(anyString())).thenReturn("12345");
        when(sshClient.newLocalPortForwarder(any(Parameters.class), any(ServerSocket.class)))
                .thenReturn(forwarder);
        when(forwarder.isRunning()).thenReturn(true);

        manager.startTunnel("ns1/my-svc", "ns1", "my-svc", 8080);
        manager.stopTunnel("ns1/my-svc");

        // Should try to kill the remote process with sudo first
        verify(sshConnection).executeCommand(eq("kill 12345"), eq(true));
        // Should close the local forwarder
        verify(forwarder).close();
    }

    @Test
    void stopTunnelForUnknownIdDoesNothing() {
        // Should not throw
        manager.stopTunnel("nonexistent");
    }

    @Test
    void stopAllClosesAllForwardersAndKillsProcesses() throws Exception {
        when(sshConnection.executeCommand(anyString())).thenReturn("12345", "67890");
        when(sshClient.newLocalPortForwarder(any(Parameters.class), any(ServerSocket.class)))
                .thenReturn(forwarder);
        when(forwarder.isRunning()).thenReturn(true);

        manager.startTunnel("ns1/svc-a", "ns1", "svc-a", 8080);
        manager.startTunnel("ns1/svc-b", "ns1", "svc-b", 9090);

        manager.stopAll();

        // killRemoteProcess tries sudo first
        verify(sshConnection).executeCommand(eq("kill 12345"), eq(true));
        verify(sshConnection).executeCommand(eq("kill 67890"), eq(true));
        verify(forwarder, times(2)).close();
    }

    @Test
    void stopAllIsSafeWhenNoTunnels() {
        // Should not throw
        manager.stopAll();
    }

    @Test
    void startTunnelThrowsOnSshClientError() throws Exception {
        when(sshConnection.executeCommand(anyString())).thenReturn("12345");
        when(sshClient.newLocalPortForwarder(any(Parameters.class), any(ServerSocket.class)))
                .thenThrow(new RuntimeException("SSH connection lost"));

        assertThrows(RuntimeException.class,
                () -> manager.startTunnel("ns/svc", "ns", "svc", 80));
    }

    @Test
    void startTunnelThrowsOnInvalidPid() throws Exception {
        when(sshConnection.executeCommand(anyString())).thenReturn("not-a-number");

        assertThrows(IOException.class,
                () -> manager.startTunnel("ns/svc", "ns", "svc", 80));
    }

    @Test
    void startTunnelThrowsOnEmptyOutput() throws Exception {
        when(sshConnection.executeCommand(anyString())).thenReturn("");

        assertThrows(IOException.class,
                () -> manager.startTunnel("ns/svc", "ns", "svc", 80));
    }
}
