package de.in.jnc.connection.browser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.in.jnc.terminal.SshConnection;

/**
 * Discovers HTTP web service endpoints on a Kubernetes cluster by running
 * {@code kubectl} commands over an SSH connection.
 * <p>
 * Queries both Services ({@code kubectl get svc --all-namespaces -o json}) and
 * Ingress resources ({@code kubectl get ingress --all-namespaces -o json}) and
 * classifies each discovered endpoint by its {@link AccessType}.
 */
public class K8sEndpointDiscoverer {

    private static final Logger LOGGER = LogManager.getLogger(K8sEndpointDiscoverer.class);

    /**
     * Web-related port numbers that are likely to host HTTP/HTTPS services.
     */
    private static final Set<Integer> WEB_PORTS = Set.of(
            80, 443, 3000, 5000, 8080, 8443, 9090
    );

    /**
     * Port range lower bound for heuristic web-port detection.
     */
    private static final int WEB_PORT_RANGE_START = 8080;
    private static final int WEB_PORT_RANGE_END = 9090;

    private final SshConnection sshConnection;
    private final ObjectMapper mapper;

    private int lastNamespaceCount;

    /**
     * Creates a new discoverer bound to the given SSH connection.
     *
     * @param sshConnection an established SSH connection to a cluster node
     */
    public K8sEndpointDiscoverer(SshConnection sshConnection) {
        this.sshConnection = sshConnection;
        this.mapper = new ObjectMapper();
    }

    /**
     * Runs the full discovery cycle: queries the cluster via kubectl, parses
     * the response, and returns classified {@link Endpoint} objects.
     *
     * @return list of discovered endpoints (may be empty)
     * @throws IOException if the SSH command fails or JSON parsing fails
     */
    public List<Endpoint> discover() throws IOException {
        LOGGER.info("Discovering Kubernetes web service endpoints\u2026");
        LOGGER.debug("Attempting kubectl commands \u2013 trying without sudo first, then with sudo -S + password");

        String svcJson = executeKubectl("get svc --all-namespaces -o json");
        List<Endpoint> services = parseServices(svcJson);

        String ingressJson = executeKubectl("get ingress --all-namespaces -o json");
        List<Endpoint> ingresses = parseIngresses(ingressJson);

        // Merge, preferring Ingress endpoints over Service endpoints
        // when they point to the same service name in the same namespace.
        List<Endpoint> merged = mergeEndpoints(services, ingresses);

        // Determine node IP for NodePort services
        String nodeIp = resolveNodeIp();

        // Build final URLs
        List<Endpoint> result = buildUrls(merged, nodeIp);

        // Count unique namespaces for UI view selection
        lastNamespaceCount = (int) result.stream()
                .map(Endpoint::namespace)
                .filter(ns -> ns != null && !ns.isEmpty())
                .distinct()
                .count();

        LOGGER.info("Discovered {} endpoints across {} namespace(s)",
                result.size(), lastNamespaceCount);
        return result;
    }

    /**
     * Returns the number of unique namespaces found during the last
     * {@link #discover()} call. Used by the UI to decide between flat and
     * grouped view.
     */
    public int getNamespaceCount() {
        return lastNamespaceCount;
    }

    // ── Package-private for testability ────────────────────────────────

    /**
     * Parses {@code kubectl get svc --all-namespaces -o json} output.
     *
     * @param jsonOutput the raw JSON from kubectl
     * @return list of endpoints discovered from Services
     * @throws IOException if JSON parsing fails
     */
    List<Endpoint> parseServices(String jsonOutput) throws IOException {
        if (jsonOutput == null || jsonOutput.isBlank()) {
            return Collections.emptyList();
        }
        JsonNode root = mapper.readTree(jsonOutput);
        JsonNode items = root.get("items");
        if (items == null || !items.isArray()) {
            return Collections.emptyList();
        }

        List<Endpoint> endpoints = new ArrayList<>();
        for (JsonNode item : items) {
            String namespace = pathAsText(item, "metadata", "namespace");
            String name = pathAsText(item, "metadata", "name");
            String type = pathAsText(item, "spec", "type");

            if (name == null || type == null) {
                continue;
            }

            JsonNode ports = item.get("spec").get("ports");
            if (ports == null || !ports.isArray()) {
                continue;
            }

            for (JsonNode portNode : ports) {
                int port = portNode.get("port").asInt();
                if (!isWebPort(port)) {
                    continue;
                }

                String displayName = buildDisplayName(name, port);
                int nodePort = portNode.has("nodePort")
                        ? portNode.get("nodePort").asInt(0)
                        : 0;

                AccessType accessType;
                switch (type) {
                    case "NodePort" -> accessType = AccessType.NODE_PORT;
                    case "LoadBalancer" -> accessType = AccessType.INGRESS;
                    default -> accessType = AccessType.TUNNEL_REQUIRED;
                }

                // For LoadBalancer services, try to extract the hostname
                String lbHostname = extractLbHostname(item);
                String url = (lbHostname != null)
                        ? "http://" + lbHostname + ":" + port
                        : name + "." + namespace + ".svc.cluster.local:" + port;

                endpoints.add(new Endpoint(displayName, url, nodePort > 0 ? nodePort : port,
                        accessType, namespace, name));
            }
        }
        return endpoints;
    }

    /**
     * Parses {@code kubectl get ingress --all-namespaces -o json} output.
     *
     * @param jsonOutput the raw JSON from kubectl
     * @return list of endpoints discovered from Ingress resources
     * @throws IOException if JSON parsing fails
     */
    List<Endpoint> parseIngresses(String jsonOutput) throws IOException {
        if (jsonOutput == null || jsonOutput.isBlank()) {
            return Collections.emptyList();
        }
        JsonNode root = mapper.readTree(jsonOutput);
        JsonNode items = root.get("items");
        if (items == null || !items.isArray()) {
            return Collections.emptyList();
        }

        List<Endpoint> endpoints = new ArrayList<>();
        for (JsonNode item : items) {
            String namespace = pathAsText(item, "metadata", "namespace");
            String name = pathAsText(item, "metadata", "name");

            JsonNode rules = item.get("spec").get("rules");
            if (rules == null || !rules.isArray()) {
                continue;
            }

            for (JsonNode rule : rules) {
                String host = rule.has("host") ? rule.get("host").asText() : null;
                if (host == null || host.isBlank()) {
                    continue;
                }

                JsonNode httpPaths = rule.get("http").get("paths");
                if (httpPaths == null || !httpPaths.isArray()) {
                    continue;
                }

                for (JsonNode path : httpPaths) {
                    int port = 80; // default for HTTP
                    JsonNode backend = path.get("backend");
                    if (backend != null && backend.has("service")) {
                        JsonNode svc = backend.get("service");
                        if (svc.has("port") && svc.get("port").has("number")) {
                            port = svc.get("port").get("number").asInt(80);
                        }
                    }

                    String displayName = buildDisplayName(name, port);
                    String url = "https://" + host;
                    if (port != 443) {
                        url += ":" + port;
                    }

                    endpoints.add(new Endpoint(displayName, url, port,
                            AccessType.INGRESS, namespace, name));
                }
            }
        }
        return endpoints;
    }

    /**
     * Merges Ingress endpoints into the service list. If an Ingress endpoint
     * exists for the same (namespace, displayName), it replaces the
     * Service-discovered entry (Ingress is usually the intended way to
     * access the service).
     */
    static List<Endpoint> mergeEndpoints(List<Endpoint> services, List<Endpoint> ingresses) {
        if (ingresses.isEmpty()) {
            return services;
        }
        if (services.isEmpty()) {
            return ingresses;
        }

        // Build a set of (namespace, displayName) pairs covered by Ingress
        Set<String> ingressKeys = ingresses.stream()
                .map(e -> e.namespace() + "/" + e.displayName())
                .collect(Collectors.toSet());

        // Filter out services that are covered by an Ingress
        List<Endpoint> result = new ArrayList<>(services.stream()
                .filter(e -> !ingressKeys.contains(e.namespace() + "/" + e.displayName()))
                .toList());
        result.addAll(ingresses);
        return result;
    }

    // ── Internal helpers ───────────────────────────────────────────────

    /**
     * Executes a kubectl command on the remote host.
     * <p>
     * Tries the command without sudo first (works when the remote user has
     * a world-readable kubeconfig, e.g. after copying from
     * {@code /etc/rancher/k3s/k3s.yaml}). Falls back to
     * {@code sudo -S kubectl ...} which pipes the SSH password through
     * stdin (supports password-based sudo in non-TTY session channels).
     *
     * @param args the kubectl arguments (e.g. {@code "get svc --all-namespaces -o json"})
     * @return the command output
     * @throws IOException if both attempts fail
     */
    private String executeKubectl(String args) throws IOException {
        String cmdNoSudo = "kubectl " + args;
        try {
            return sshConnection.executeCommand(cmdNoSudo);
        } catch (IOException e) {
            LOGGER.debug("kubectl without sudo failed ({}), retrying with sudo -S\u2026", e.getMessage());
        }
        // Fallback: with sudo -S (pipes password via stdin)
        String cmdSudo = "kubectl " + args;
        try {
            return sshConnection.executeCommand(cmdSudo, true);
        } catch (IOException e) {
            LOGGER.warn("kubectl also failed with sudo -S: {}", e.getMessage());
            throw new IOException(
                    "kubectl discovery failed. The remote user's password was used with sudo -S, "
                    + "but the command still failed. Possible causes:\n"
                    + "  1. The password is incorrect or has expired\n"
                    + "  2. The remote user is not in the sudoers group\n"
                    + "  3. The kubeconfig at /etc/rancher/k3s/k3s.yaml is not readable\n"
                    + "Alternative: copy the kubeconfig to the user's home directory:\n"
                    + "  ssh> sudo cp /etc/rancher/k3s/k3s.yaml ~/.kube/config\n"
                    + "  ssh> sudo chown $USER ~/.kube/config\n"
                    + "Original error: " + e.getMessage(), e);
        }
    }


    /**
     * Resolves the node IP by running {@code hostname -I} on the remote host.
     * Falls back to the SSH connection's host address.
     */
    private String resolveNodeIp() throws IOException {
        try {
            // hostname -I does not need elevated privileges
            String output = sshConnection.executeCommand("hostname -I");
            if (output != null && !output.isBlank()) {
                // hostname -I returns space-separated IPs; take the first one
                return output.split("\\s+")[0];
            }
        } catch (IOException e) {
            LOGGER.warn("Could not resolve node IP via hostname -I, falling back to SSH host", e);
        }
        // Fallback: use the SSH connection target as the node address
        return sshConnection.getHost();
    }

    /**
     * Builds final reachable URLs based on access type and replaces the
     * placeholder URL in each endpoint.
     */
    private List<Endpoint> buildUrls(List<Endpoint> endpoints, String nodeIp) {
        return endpoints.stream()
                .map(ep -> {
                    String url = switch (ep.accessType()) {
                        case NODE_PORT -> "http://" + nodeIp + ":" + ep.port();
                        case TUNNEL_REQUIRED -> "http://localhost:" + ep.port();
                        case INGRESS -> ep.url(); // already set during parsing
                    };
                    return new Endpoint(ep.displayName(), url, ep.port(),
                            ep.accessType(), ep.namespace(), ep.serviceName());
                })
                .toList();
    }

    private static String buildDisplayName(String serviceName, int port) {
        String base = serviceName.substring(0, 1).toUpperCase() + serviceName.substring(1);
        // Append port if it's not the default for the protocol
        if (port != 80 && port != 443) {
            return base + " (" + port + ")";
        }
        return base;
    }

    private static boolean isWebPort(int port) {
        return WEB_PORTS.contains(port)
                || (port >= WEB_PORT_RANGE_START && port <= WEB_PORT_RANGE_END);
    }

    private static String pathAsText(JsonNode node, String first, String second) {
        JsonNode child = node.get(first);
        if (child == null) {
            return null;
        }
        JsonNode target = child.get(second);
        return target != null ? target.asText() : null;
    }

    private static String extractLbHostname(JsonNode serviceItem) {
        try {
            JsonNode ingressList = serviceItem.get("status")
                    .get("loadBalancer").get("ingress");
            if (ingressList != null && ingressList.isArray() && ingressList.size() > 0) {
                JsonNode first = ingressList.get(0);
                if (first.has("hostname")) {
                    return first.get("hostname").asText();
                }
                if (first.has("ip")) {
                    return first.get("ip").asText();
                }
            }
        } catch (Exception e) {
            // no LB status
        }
        return null;
    }
}
