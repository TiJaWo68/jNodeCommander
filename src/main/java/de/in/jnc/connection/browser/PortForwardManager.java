package de.in.jnc.connection.browser;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.in.jnc.terminal.SshConnection;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder;
import net.schmizz.sshj.connection.channel.direct.Parameters;

/**
 * Manages SSH-based port-forward tunnels to Kubernetes services that are not
 * directly reachable (ClusterIP / TUNNEL_REQUIRED).
 * <p>
 * Uses a two-step approach:
 * <ol>
 *   <li>Starts {@code kubectl port-forward} on the <b>remote</b> host via
 *       {@code nohup ... & echo $!}, which runs in the background and binds
 *       to {@code localhost:<remotePort>} on the remote machine.</li>
 *   <li>Creates a local {@link LocalPortForwarder} through the SSH connection
 *       that forwards {@code localhost:<localPort>} to
 *       {@code localhost:<remotePort>} on the remote host (where kubectl is
 *       listening).</li>
 * </ol>
 * This avoids requiring {@code kubectl} on the local Windows machine and
 * sidesteps the issue that K8s internal DNS names
 * ({@code <service>.<namespace>.svc.cluster.local}) are not resolvable
 * from the K3s node's host OS.
 * <p>
 * Each tunnel runs as a daemon thread and is tracked by an endpoint identifier.
 * All tunnels are stopped when the connection is closed.
 */
public class PortForwardManager {

    private static final Logger LOGGER = LogManager.getLogger(PortForwardManager.class);

    /**
     * Starting port for the local tunnel port range.
     */
    private static final int LOCAL_PORT_START = 49000;

    /**
     * Maximum number of concurrent tunnels.
     */
    private static final int MAX_TUNNELS = 100;

    /**
     * Time to wait for the remote kubectl port-forward process to bind its port.
     */
    private static final long KUBECTL_STARTUP_WAIT_MS = 2000;

    private final SshConnection sshConnection;
    private final SSHClient sshClient;
    private final Map<String, TunnelInfo> activeTunnels;
    private final AtomicInteger nextPort;

    /**
     * Creates a new tunnel manager bound to the given SSH connection.
     *
     * @param sshConnection an established SSH connection
     */
    public PortForwardManager(SshConnection sshConnection) {
        this.sshConnection = sshConnection;
        this.sshClient = sshConnection.getSshClient();
        this.activeTunnels = new ConcurrentHashMap<>();
        this.nextPort = new AtomicInteger(LOCAL_PORT_START);
    }

    /**
     * Starts an SSH port-forward tunnel for the given Kubernetes service.
     * <p>
     * First, {@code kubectl port-forward} is launched on the remote host in the
     * background (via {@code nohup ... & echo $!}). Then a local port forwarder
     * is created through the SSH connection, forwarding
     * {@code localhost:<localPort>} to the remote {@code localhost:<remotePort>}
     * where kubectl is listening. If a tunnel for the same endpoint already
     * exists, its local port is returned.
     *
     * @param endpointId  unique identifier for the endpoint (e.g. {@code namespace/name})
     * @param namespace   the Kubernetes namespace
     * @param serviceName the Kubernetes service name (not the display name)
     * @param remotePort  the remote service port
     * @return the local port on which the tunnel is listening
     * @throws IOException if the tunnel cannot be created
     */
    public int startTunnel(String endpointId, String namespace,
                           String serviceName, int remotePort) throws IOException {
        // Check if tunnel already exists
        TunnelInfo existing = activeTunnels.get(endpointId);
        if (existing != null && existing.forwarder.isRunning()) {
            LOGGER.debug("Re-using existing tunnel for {} on local port {}",
                    endpointId, existing.localPort);
            return existing.localPort;
        }

        int localPort = allocateLocalPort();
        String safeId = endpointId.replace('/', '-').replace(' ', '_');
        String logFile = "/tmp/jnc-pf-" + safeId + ".log";

        // Step 1: Start kubectl port-forward on the remote host in the background.
        // The nohup + & + echo $! pattern runs the command asynchronously and
        // immediately returns its PID.
        String portForwardCmd = "kubectl port-forward -n " + namespace
                + " svc/" + serviceName + " " + remotePort;
        String bgCmd = "nohup " + portForwardCmd + " > " + logFile + " 2>&1 & echo $!";

        LOGGER.info("Starting remote kubectl port-forward: {} (background, log: {})",
                portForwardCmd, logFile);

        int pid = startRemotePortForward(bgCmd);

        // Step 2: Wait briefly for kubectl to start and bind to the port
        try {
            Thread.sleep(KUBECTL_STARTUP_WAIT_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        // Step 3: Create a local port forwarder that tunnels through SSH to
        // localhost:remotePort on the remote host (where kubectl is listening)
        LOGGER.info("Creating SSH tunnel: localhost:{} -> localhost:{} (remote, kubectl pid={})",
                localPort, remotePort, pid);

        Parameters params = new Parameters("localhost", localPort,
                "localhost", remotePort);
        ServerSocket serverSocket = createServerSocket(localPort);
        LocalPortForwarder forwarder = sshClient.newLocalPortForwarder(params, serverSocket);

        Thread forwardThread = new Thread(() -> {
            try {
                forwarder.listen();
            } catch (IOException e) {
                if (forwarder.isRunning()) {
                    LOGGER.error("SSH port forwarder failed for {}: {}", endpointId, e.getMessage());
                } else {
                    LOGGER.debug("SSH port forwarder for {} stopped", endpointId);
                }
            }
        }, "port-fwd-" + safeId);
        forwardThread.setDaemon(true);
        forwardThread.start();

        TunnelInfo info = new TunnelInfo(forwarder, forwardThread, localPort, pid, logFile);
        activeTunnels.put(endpointId, info);
        LOGGER.info("Tunnel for {} is listening on localhost:{} (kubectl pid={})",
                endpointId, localPort, pid);
        return localPort;
    }

    /**
     * Stops the tunnel for the given endpoint.
     * Kills the remote kubectl process and closes the local forwarder.
     *
     * @param endpointId the endpoint identifier
     */
    public void stopTunnel(String endpointId) {
        TunnelInfo info = activeTunnels.remove(endpointId);
        if (info != null) {
            killRemoteProcess(info);
            closeForwarder(info);
        }
    }

    /**
     * Stops all active tunnels. Called when the connection is closed.
     */
    public void stopAll() {
        LOGGER.info("Stopping all SSH tunnels ({} active)", activeTunnels.size());
        activeTunnels.values().forEach(info -> {
            killRemoteProcess(info);
            closeForwarder(info);
        });
        activeTunnels.clear();
    }

    // ── Internal helpers ──────────────────────────────────────────────

    /**
     * Attempts to start the kubectl port-forward command on the remote host.
     * Tries without sudo first, then falls back to sudo -S.
     */
    private int startRemotePortForward(String bgCmd) throws IOException {
        // Try without sudo first
        try {
            String output = sshConnection.executeCommand(bgCmd);
            return parsePid(output, bgCmd);
        } catch (IOException e) {
            LOGGER.debug("kubectl port-forward without sudo failed ({}), retrying with sudo -S\u2026",
                    e.getMessage());
        }
        // Fallback: with sudo -S
        try {
            String output = sshConnection.executeCommand(bgCmd, true);
            return parsePid(output, bgCmd);
        } catch (IOException e) {
            LOGGER.warn("kubectl port-forward also failed with sudo -S: {}", e.getMessage());
            throw new IOException(
                    "Failed to start remote kubectl port-forward. "
                    + "The remote user's password was used with sudo -S, "
                    + "but the command still failed.\n"
                    + "Command: " + bgCmd + "\n"
                    + "Original error: " + e.getMessage(), e);
        }
    }

    /**
     * Parses the PID from the output of the {@code echo $!} command.
     */
    private static int parsePid(String output, String command) throws IOException {
        if (output == null || output.isBlank()) {
            throw new IOException("Could not determine PID of remote kubectl process.\n"
                    + "Command: " + command);
        }
        String trimmed = output.trim();
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            throw new IOException("Unexpected output from remote command (expected PID): \""
                    + trimmed + "\"\nCommand: " + command, e);
        }
    }

    /**
     * Kills the remote kubectl port-forward process by PID.
     */
    private void killRemoteProcess(TunnelInfo info) {
        try {
            // Try with sudo first (if started with sudo), then without
            String killCmd = "kill " + info.pid;
            try {
                sshConnection.executeCommand(killCmd, true);
            } catch (IOException e) {
                // Try without sudo
                sshConnection.executeCommand(killCmd);
            }
            LOGGER.debug("Killed remote kubectl process pid={} for tunnel on local port {}",
                    info.pid, info.localPort);
        } catch (Exception e) {
            LOGGER.warn("Could not kill remote kubectl process pid={}: {}",
                    info.pid, e.getMessage());
        }
    }

    /**
     * Creates a {@link ServerSocket} bound to the given port.
     * <p>
     * Extracted for testability – subclasses or overrides can return a mock.
     */
    ServerSocket createServerSocket(int port) throws IOException {
        return new ServerSocket(port);
    }

    private int allocateLocalPort() {
        int port = nextPort.getAndIncrement();
        if (port >= LOCAL_PORT_START + MAX_TUNNELS) {
            // Wrap around if we exceeded the range
            nextPort.set(LOCAL_PORT_START);
            port = nextPort.getAndIncrement();
        }
        return port;
    }

    private void closeForwarder(TunnelInfo info) {
        try {
            if (info.forwarder.isRunning()) {
                info.forwarder.close();
            }
        } catch (Exception e) {
            LOGGER.warn("Error stopping SSH tunnel on local port {}: {}",
                    info.localPort, e.getMessage());
        }
    }

    /**
     * Holds the forwarder, thread, local port, remote PID, and log file for an
     * active tunnel.
     */
    private record TunnelInfo(LocalPortForwarder forwarder, Thread thread,
                              int localPort, int pid, String logFile) {
    }
}
