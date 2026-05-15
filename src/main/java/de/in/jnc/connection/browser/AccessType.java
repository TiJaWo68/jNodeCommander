package de.in.jnc.connection.browser;

/**
 * Classifies how a discovered web service endpoint can be reached from the
 * jnc client machine.
 */
public enum AccessType {

    /**
     * The service is exposed via a Kubernetes {@code NodePort}.
     * The URL is directly reachable from the jnc client using the node's IP
     * and the allocated NodePort.
     */
    NODE_PORT,

    /**
     * The service is of type {@code ClusterIP} (or a non-NodePort / non-LB
     * type). It is <b>not</b> directly reachable from the jnc client and
     * requires a {@code kubectl port-forward} tunnel.
     */
    TUNNEL_REQUIRED,

    /**
     * The service is exposed via a {@code LoadBalancer} or an {@code Ingress}
     * resource with a publicly resolvable hostname. The URL is directly
     * reachable from the jnc client.
     */
    INGRESS
}
