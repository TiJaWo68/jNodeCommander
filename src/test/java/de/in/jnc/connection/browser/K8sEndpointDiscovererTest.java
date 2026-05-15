package de.in.jnc.connection.browser;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import de.in.jnc.terminal.SshConnection;

/**
 * Unit tests for {@link K8sEndpointDiscoverer}.
 * <p>
 * Tests parseServices(), parseIngresses(), and mergeEndpoints() with
 * synthetic kubectl JSON output.
 */
@ExtendWith(MockitoExtension.class)
class K8sEndpointDiscovererTest {

    @Mock
    private SshConnection sshConnection;

    private K8sEndpointDiscoverer discoverer;

    @BeforeEach
    void setUp() {
        discoverer = new K8sEndpointDiscoverer(sshConnection);
    }

    // ── parseServices ────────────────────────────────────────────────────

    @Test
    void parseServices_returnsEmptyList_forBlankInput() throws IOException {
        assertTrue(discoverer.parseServices("").isEmpty());
        assertTrue(discoverer.parseServices("   ").isEmpty());
        assertTrue(discoverer.parseServices(null).isEmpty());
    }

    @Test
    void parseServices_returnsEmptyList_forEmptyItems() throws IOException {
        String json = """
                { "items": [] }
                """;
        assertTrue(discoverer.parseServices(json).isEmpty());
    }

    @Test
    void parseServices_classifiesNodePortService() throws IOException {
        String json = """
                {
                  "items": [{
                    "metadata": { "namespace": "master", "name": "keycloak" },
                    "spec": {
                      "type": "NodePort",
                      "ports": [{ "port": 8080, "nodePort": 31080, "protocol": "TCP" }]
                    }
                  }]
                }
                """;
        List<Endpoint> eps = discoverer.parseServices(json);
        assertEquals(1, eps.size());
        Endpoint ep = eps.get(0);
        assertEquals("Keycloak (8080)", ep.displayName());
        assertEquals(31080, ep.port()); // nodePort is used as reachable port
        assertEquals(AccessType.NODE_PORT, ep.accessType());
        assertEquals("master", ep.namespace());
        assertEquals("keycloak", ep.serviceName());
    }

    @Test
    void parseServices_classifiesClusterIpService() throws IOException {
        String json = """
                {
                  "items": [{
                    "metadata": { "namespace": "team-a", "name": "du-viewer" },
                    "spec": {
                      "type": "ClusterIP",
                      "ports": [{ "port": 3000, "protocol": "TCP" }]
                    }
                  }]
                }
                """;
        List<Endpoint> eps = discoverer.parseServices(json);
        assertEquals(1, eps.size());
        assertEquals(AccessType.TUNNEL_REQUIRED, eps.get(0).accessType());
        assertEquals("team-a", eps.get(0).namespace());
        assertEquals("du-viewer", eps.get(0).serviceName());
    }

    @Test
    void parseServices_classifiesLoadBalancerService() throws IOException {
        String json = """
                {
                  "items": [{
                    "metadata": { "namespace": "default", "name": "myapp" },
                    "spec": {
                      "type": "LoadBalancer",
                      "ports": [{ "port": 80, "protocol": "TCP" }]
                    },
                    "status": {
                      "loadBalancer": {
                        "ingress": [{ "hostname": "myapp.example.com" }]
                      }
                    }
                  }]
                }
                """;
        List<Endpoint> eps = discoverer.parseServices(json);
        assertEquals(1, eps.size());
        Endpoint ep = eps.get(0);
        assertEquals(AccessType.INGRESS, ep.accessType());
        assertTrue(ep.url().contains("myapp.example.com"));
        assertEquals("myapp", ep.serviceName());
    }

    @Test
    void parseServices_filtersNonWebPorts() throws IOException {
        String json = """
                {
                  "items": [{
                    "metadata": { "namespace": "default", "name": "postgres" },
                    "spec": {
                      "type": "ClusterIP",
                      "ports": [{ "port": 5432, "protocol": "TCP" }]
                    }
                  }]
                }
                """;
        List<Endpoint> eps = discoverer.parseServices(json);
        assertTrue(eps.isEmpty(), "Non-web ports should be filtered out");
    }

    @Test
    void parseServices_handlesMultiplePorts() throws IOException {
        String json = """
                {
                  "items": [{
                    "metadata": { "namespace": "default", "name": "webapp" },
                    "spec": {
                      "type": "NodePort",
                      "ports": [
                        { "port": 80, "nodePort": 30080, "protocol": "TCP" },
                        { "port": 5432, "protocol": "TCP" }
                      ]
                    }
                  }]
                }
                """;
        List<Endpoint> eps = discoverer.parseServices(json);
        assertEquals(1, eps.size(), "Only web port 80 should be kept");
        assertEquals(30080, eps.get(0).port());
    }

    // ── parseIngresses ───────────────────────────────────────────────────

    @Test
    void parseIngresses_returnsEmptyList_forBlankInput() throws IOException {
        assertTrue(discoverer.parseIngresses("").isEmpty());
        assertTrue(discoverer.parseIngresses(null).isEmpty());
    }

    @Test
    void parseIngresses_extractsHostnameAndPort() throws IOException {
        String json = """
                {
                  "items": [{
                    "metadata": { "namespace": "team-b", "name": "du-api" },
                    "spec": {
                      "rules": [{
                        "host": "du-api.example.com",
                        "http": {
                          "paths": [{
                            "pathType": "Prefix",
                            "path": "/",
                            "backend": {
                              "service": {
                                "name": "du-api",
                                "port": { "number": 8443 }
                              }
                            }
                          }]
                        }
                      }]
                    }
                  }]
                }
                """;
        List<Endpoint> eps = discoverer.parseIngresses(json);
        assertEquals(1, eps.size());
        Endpoint ep = eps.get(0);
        assertEquals("Du-api (8443)", ep.displayName());
        assertEquals("https://du-api.example.com:8443", ep.url());
        assertEquals(8443, ep.port());
        assertEquals(AccessType.INGRESS, ep.accessType());
        assertEquals("team-b", ep.namespace());
        assertEquals("du-api", ep.serviceName());
    }

    @Test
    void parseIngresses_usesDefaultPort443_whenNotSpecified() throws IOException {
        String json = """
                {
                  "items": [{
                    "metadata": { "namespace": "default", "name": "web" },
                    "spec": {
                      "rules": [{
                        "host": "web.example.com",
                        "http": {
                          "paths": [{
                            "pathType": "Prefix",
                            "path": "/",
                            "backend": {
                              "service": {
                                "name": "web",
                                "port": { "number": 443 }
                              }
                            }
                          }]
                        }
                      }]
                    }
                  }]
                }
                """;
        List<Endpoint> eps = discoverer.parseIngresses(json);
        assertEquals(1, eps.size());
        // Port 443 should not be appended to URL
        assertEquals("https://web.example.com", eps.get(0).url());
    }

    // ── mergeEndpoints ───────────────────────────────────────────────────

    @Test
    void mergeEndpoints_returnsServices_whenNoIngresses() {
        List<Endpoint> svcs = List.of(
                new Endpoint("Svc1", "http://svc1", 80, AccessType.NODE_PORT, "default", "svc1"));
        List<Endpoint> result = K8sEndpointDiscoverer.mergeEndpoints(svcs, List.of());
        assertEquals(1, result.size());
        assertEquals("Svc1", result.get(0).displayName());
    }

    @Test
    void mergeEndpoints_returnsIngresses_whenNoServices() {
        List<Endpoint> ingresses = List.of(
                new Endpoint("Ing1", "https://ing1", 443, AccessType.INGRESS, "default", "ing1"));
        List<Endpoint> result = K8sEndpointDiscoverer.mergeEndpoints(List.of(), ingresses);
        assertEquals(1, result.size());
    }

    @Test
    void mergeEndpoints_ingressOverridesService_withSameNamespaceAndName() {
        List<Endpoint> svcs = List.of(
                new Endpoint("MyApp", "http://node:30080", 30080, AccessType.NODE_PORT, "default", "myapp"));
        List<Endpoint> ingresses = List.of(
                new Endpoint("MyApp", "https://myapp.example.com", 443, AccessType.INGRESS, "default", "myapp"));
        List<Endpoint> result = K8sEndpointDiscoverer.mergeEndpoints(svcs, ingresses);
        assertEquals(1, result.size());
        assertEquals(AccessType.INGRESS, result.get(0).accessType());
        assertEquals("https://myapp.example.com", result.get(0).url());
    }

    @Test
    void mergeEndpoints_keepsBoth_whenDifferentNamespaces() {
        List<Endpoint> svcs = List.of(
                new Endpoint("MyApp", "http://node:30080", 30080, AccessType.NODE_PORT, "ns-a", "myapp"));
        List<Endpoint> ingresses = List.of(
                new Endpoint("MyApp", "https://myapp.example.com", 443, AccessType.INGRESS, "ns-b", "myapp"));
        List<Endpoint> result = K8sEndpointDiscoverer.mergeEndpoints(svcs, ingresses);
        assertEquals(2, result.size());
    }

    // ── getNamespaceCount ────────────────────────────────────────────────

    @Test
    void getNamespaceCount_returnsZero_beforeDiscovery() {
        assertEquals(0, discoverer.getNamespaceCount());
    }
}
