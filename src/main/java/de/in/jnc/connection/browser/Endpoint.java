package de.in.jnc.connection.browser;

/**
 * Represents a discovered web service endpoint on the Kubernetes cluster.
 *
 * @param displayName human-readable service name (e.g. {@code "Keycloak (8080)"})
 * @param url         the URL through which the service can be reached
 *                    (e.g. {@code "http://node-ip:31080"} or
 *                    {@code "https://keycloak.example.com"})
 * @param port        the reachable port
 * @param accessType  how the service is reachable from the jnc client
 * @param namespace   the Kubernetes namespace the service belongs to
 * @param serviceName the original Kubernetes service name (used for kubectl commands)
 */
public record Endpoint(
        String displayName,
        String url,
        int port,
        AccessType accessType,
        String namespace,
        String serviceName
) {

    /**
     * Returns a short string for display in the popup menu, e.g.
     * {@code ":31080"} for a NodePort or {@code ":3000"} for a tunnel-required
     * service.
     */
    public String portLabel() {
        return ":" + port;
    }
}
