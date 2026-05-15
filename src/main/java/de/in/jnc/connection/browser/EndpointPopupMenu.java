package de.in.jnc.connection.browser;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JSeparator;

/**
 * A dynamic {@link JPopupMenu} that displays discovered Kubernetes web service
 * endpoints in either a flat (grouped by {@link AccessType}) or grouped
 * (by namespace) view.
 * <p>
 * Users can click an endpoint to open it in a browser tab, toggle between flat
 * and grouped views, or trigger a re-discovery.
 */
public class EndpointPopupMenu extends JPopupMenu {

    /**
     * Threshold for disabling the grouping toggle: when there are 2 or fewer
     * unique namespaces, grouping offers little benefit.
     */
    private static final int DISABLE_GROUPING_THRESHOLD = 2;

    /**
     * Rebuilds the entire popup menu with the given endpoints and view mode.
     * <p>
     * The menu is constructed from scratch each time (no caching) so the
     * displayed data is always current.
     *
     * @param endpoints    discovered endpoints (may be empty, not null)
     * @param groupByNs    {@code true} = group by namespace,
     *                     {@code false} = group by access type (flat)
     * @param onNewTab     called when the "New Browser Tab" item is clicked
     * @param onUrlClick   called when an endpoint item is clicked; receives
     *                     the {@link Endpoint} the user selected
     * @param onToggleView called when the grouping toggle is changed; receives
     *                     the new {@code groupByNs} value
     * @param onRefresh    called when the "Neu laden" (refresh) item is clicked
     */
    public void rebuild(List<Endpoint> endpoints, boolean groupByNs,
                        Runnable onNewTab,
                        Consumer<Endpoint> onUrlClick,
                        Consumer<Boolean> onToggleView,
                        Runnable onRefresh) {
        removeAll();

        // ── New Browser Tab (always first) ────────────────────────────
        JMenuItem newTabItem = new JMenuItem("\uD83D\uDD0D  New Browser Tab");
        newTabItem.addActionListener(e -> onNewTab.run());
        add(newTabItem);

        if (endpoints.isEmpty()) {
            // Nothing more to show, but still add separator + refresh
            add(new JSeparator());
            addRefreshItem(onRefresh);
            return;
        }

        add(new JSeparator());

        if (groupByNs) {
            buildGroupedView(endpoints, onUrlClick);
        } else {
            buildFlatView(endpoints, onUrlClick);
        }

        add(new JSeparator());

        // ── Toggle: Group by Namespace ───────────────────────────────
        long namespaceCount = endpoints.stream()
                .map(Endpoint::namespace)
                .filter(ns -> ns != null && !ns.isEmpty())
                .distinct()
                .count();

        JCheckBoxMenuItem toggleItem = new JCheckBoxMenuItem(
                "\u2611  Group by Namespace", groupByNs);
        toggleItem.setEnabled(namespaceCount > DISABLE_GROUPING_THRESHOLD);
        toggleItem.addActionListener(e -> {
            if (onToggleView != null) {
                onToggleView.accept(toggleItem.isSelected());
            }
        });
        add(toggleItem);

        // ── Refresh ──────────────────────────────────────────────────
        addRefreshItem(onRefresh);
    }

    // ── Flat view: grouped by AccessType ────────────────────────────────

    private void buildFlatView(List<Endpoint> endpoints,
                               Consumer<Endpoint> onUrlClick) {
        Map<AccessType, List<Endpoint>> byAccessType = endpoints.stream()
                .collect(Collectors.groupingBy(Endpoint::accessType));

        // 📡 NodePort section
        addAccessTypeSection(byAccessType, AccessType.NODE_PORT,
                "\uD83D\uDCE1  NodePort (direkt erreichbar)",
                true, onUrlClick);

        // 🚇 Tunnel section
        addAccessTypeSection(byAccessType, AccessType.TUNNEL_REQUIRED,
                "\uD83D\uDE87  Tunnel erforderlich (port-forward)",
                true, onUrlClick);

        // 🌐 Ingress section
        addAccessTypeSection(byAccessType, AccessType.INGRESS,
                "\uD83C\uDF10  Ingress (via Hostname)",
                true, onUrlClick);
    }

    // ── Grouped view: grouped by namespace ──────────────────────────────

    private void buildGroupedView(List<Endpoint> endpoints,
                                  Consumer<Endpoint> onUrlClick) {
        Map<String, List<Endpoint>> byNamespace = endpoints.stream()
                .collect(Collectors.groupingBy(Endpoint::namespace));

        byNamespace.keySet().stream()
                .sorted()
                .forEach(ns -> {
                    JMenu nsMenu = new JMenu("\uD83D\uDCE6  " + ns);
                    List<Endpoint> nsEndpoints = byNamespace.get(ns);

                    Map<AccessType, List<Endpoint>> byAccessType = nsEndpoints
                            .stream()
                            .collect(Collectors.groupingBy(Endpoint::accessType));

                    // 📡 NodePort
                    addAccessTypeSection(byAccessType, AccessType.NODE_PORT,
                            "\uD83D\uDCE1  NodePort", false, onUrlClick)
                            .ifPresent(nsMenu::add);

                    // 🚇 Tunnel
                    addAccessTypeSection(byAccessType, AccessType.TUNNEL_REQUIRED,
                            "\uD83D\uDE87  Tunnel", false, onUrlClick)
                            .ifPresent(nsMenu::add);

                    // 🌐 Ingress
                    addAccessTypeSection(byAccessType, AccessType.INGRESS,
                            "\uD83C\uDF10  Ingress", false, onUrlClick)
                            .ifPresent(nsMenu::add);

                    add(nsMenu);
                });
    }

    // ── Internal helpers ───────────────────────────────────────────────

    /**
     * Adds a section menu for a specific access type, if there are matching
     * endpoints. In flat view the section is a JMenu showing the access type
     * header; in grouped view it is returned as an optional JMenu.
     *
     * @param byAccessType endpoints grouped by access type
     * @param type         the access type to extract
     * @param sectionLabel the display label for the section
     * @param showNamespace whether to show namespace info in endpoint items
     * @param onUrlClick   click callback
     * @return the created JMenu, or empty if no endpoints of this type
     */
    private java.util.Optional<JMenu> addAccessTypeSection(
            Map<AccessType, List<Endpoint>> byAccessType,
            AccessType type, String sectionLabel,
            boolean showNamespace,
            Consumer<Endpoint> onUrlClick) {
        List<Endpoint> eps = byAccessType.getOrDefault(type, List.of());
        if (eps.isEmpty()) {
            return java.util.Optional.empty();
        }

        JMenu sectionMenu = new JMenu(sectionLabel);
        eps.stream()
                .sorted(Comparator.comparing(Endpoint::displayName))
                .forEach(ep -> {
                    JMenuItem item = createEndpointItem(ep, showNamespace);
                    item.addActionListener(e -> onUrlClick.accept(ep));
                    sectionMenu.add(item);
                });
        add(sectionMenu);
        return java.util.Optional.of(sectionMenu);
    }

    /**
     * Creates a {@link JMenuItem} for a single endpoint.
     *
     * @param ep            the endpoint
     * @param showNamespace if {@code true}, includes the namespace in the label
     * @return a menu item with label and tooltip
     */
    private static JMenuItem createEndpointItem(Endpoint ep, boolean showNamespace) {
        String label;
        if (showNamespace) {
            label = String.format("\uD83D\uDD17  %s    %s    %s",
                    ep.displayName(),
                    ep.namespace() != null ? ep.namespace() : "",
                    ep.portLabel());
        } else {
            label = String.format("\uD83D\uDD17  %s    %s",
                    ep.displayName(),
                    ep.portLabel());
        }
        JMenuItem item = new JMenuItem(label);
        item.setToolTipText(ep.url());
        return item;
    }

    /**
     * Adds the "🔄  Neu laden" refresh item.
     */
    private void addRefreshItem(Runnable onRefresh) {
        JMenuItem refreshItem = new JMenuItem("\uD83D\uDD04  Neu laden");
        refreshItem.addActionListener(e -> {
            if (onRefresh != null) {
                onRefresh.run();
            }
        });
        add(refreshItem);
    }
}
