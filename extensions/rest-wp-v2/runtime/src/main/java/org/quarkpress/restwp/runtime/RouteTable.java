package org.quarkpress.restwp.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.quarkpress.restwp.Arg;
import org.quarkpress.restwp.Endpoint;
import org.quarkpress.restwp.RouteBuilder;
import org.quarkpress.restwp.RouteRegistry;

/**
 * The registered routes, by namespace, in registration order, and WordPress's matching: the routes of the namespaces
 * that prefix the path first (if any namespace does, only those), the first route whose pattern matches, and within
 * it the first endpoint declaring the method (HEAD falls back to an endpoint declaring GET). With a
 * {@link NamespaceIndex}, the first route registered in a namespace is preceded by the namespace's index route
 * ({@code /<namespace>}), as WordPress inserts it.
 */
public final class RouteTable {

    /** The endpoint of a namespace's index route ({@code /<namespace>}), which the table registers itself. */
    @FunctionalInterface
    public interface NamespaceIndex {

        Endpoint endpoint(String namespace);
    }

    /**
     * A route: its pattern, its endpoints (each with the route's shared arguments merged in) and its item schema.
     */
    public record Route(String namespace, RoutePattern pattern, List<Endpoint> endpoints, Supplier<ObjectNode> schema) {

        public String key() {
            return pattern.key();
        }
    }

    /** The outcome of a match. */
    public record Match(Route route, Endpoint endpoint, Map<String, String> urlParams) {

        public MatchedRoute matchedRoute() {
            return new MatchedRoute(route, urlParams);
        }
    }

    /** A route whose pattern matched a path, with the values of its named groups. */
    public record MatchedRoute(Route route, Map<String, String> urlParams) {}

    /**
     * The longest route path, in UTF-8 bytes, that is matched at all; a longer one matches no route. WordPress has no
     * such bound; a route arrives in a URL or a form field, and no real route comes close.
     */
    public static final int MAX_ROUTE_BYTES = 2048;

    private final Map<String, List<Route>> namespaces = new LinkedHashMap<>();
    private final Map<String, Route> routes = new LinkedHashMap<>();
    private final NamespaceIndex namespaceIndex;

    /** A table without namespace index routes. */
    public RouteTable() {
        this(null);
    }

    /** A table that registers {@code namespaceIndex}'s endpoint at {@code /<namespace>} for every namespace. */
    public RouteTable(NamespaceIndex namespaceIndex) {
        this.namespaceIndex = namespaceIndex;
    }

    /** A registry adding to this table. */
    public RouteRegistry registry() {
        return this::route;
    }

    private RouteBuilder route(String namespace, String pattern) {
        Objects.requireNonNull(namespace, "namespace");
        RoutePattern parsed = RoutePattern.parse(namespace, pattern);
        String ns = RoutePattern.trimSlashes(namespace);
        return new RouteBuilder() {
            private final Map<String, Arg> shared = new LinkedHashMap<>();
            private final List<Endpoint> endpoints = new ArrayList<>();
            private Supplier<ObjectNode> schema;

            @Override
            public RouteBuilder arg(Arg arg) {
                shared.put(arg.name(), arg);
                return this;
            }

            @Override
            public RouteBuilder schema(Supplier<ObjectNode> itemSchema) {
                this.schema = Objects.requireNonNull(itemSchema, "itemSchema");
                return this;
            }

            @Override
            public RouteBuilder endpoint(Endpoint endpoint) {
                endpoints.add(Objects.requireNonNull(endpoint, "endpoint"));
                return this;
            }

            @Override
            public void register() {
                if (endpoints.isEmpty()) {
                    throw new IllegalArgumentException("route " + parsed.key() + " declares no endpoint");
                }
                shared.values().forEach(arg -> SchemaValidator.requireSupported(arg.schema()));
                for (Endpoint endpoint : endpoints) {
                    endpoint.args().forEach(arg -> SchemaValidator.requireSupported(arg.schema()));
                }
                if (schema != null) {
                    SchemaValidator.requireSupported(schema.get());
                }
                List<Endpoint> merged = new ArrayList<>(endpoints.size());
                for (Endpoint endpoint : endpoints) {
                    merged.add(withShared(endpoint));
                }
                add(ns, parsed, merged, schema);
            }

            private Endpoint withShared(Endpoint endpoint) {
                if (shared.isEmpty()) {
                    return endpoint;
                }
                Map<String, Arg> args = new LinkedHashMap<>(shared);
                for (Arg arg : endpoint.args()) {
                    args.put(arg.name(), arg);
                }
                return new Endpoint(
                        endpoint.methods(),
                        List.copyOf(args.values()),
                        endpoint.permission(),
                        endpoint.handler(),
                        endpoint.showInIndex(),
                        endpoint.allowBatch());
            }
        };
    }

    private synchronized void add(
            String namespace, RoutePattern pattern, List<Endpoint> endpoints, Supplier<ObjectNode> schema) {
        if (namespaceIndex != null && !namespace.isEmpty() && !namespaces.containsKey(namespace)) {
            // WordPress registers the namespace's index route before the namespace's first route
            namespaces.put(namespace, new ArrayList<>());
            Endpoint index = Objects.requireNonNull(namespaceIndex.endpoint(namespace), "namespace index endpoint");
            index.args().forEach(arg -> SchemaValidator.requireSupported(arg.schema()));
            put(namespace, RoutePattern.parse(namespace, ""), List.of(index), null);
        }
        put(namespace, pattern, endpoints, schema);
    }

    private void put(String namespace, RoutePattern pattern, List<Endpoint> endpoints, Supplier<ObjectNode> schema) {
        Route existing = routes.get(pattern.key());
        Route route;
        if (existing == null) {
            route = new Route(namespace, pattern, List.copyOf(endpoints), schema);
        } else {
            List<Endpoint> all = new ArrayList<>(existing.endpoints());
            all.addAll(endpoints);
            route = new Route(
                    existing.namespace(), pattern, List.copyOf(all), schema != null ? schema : existing.schema());
        }
        routes.put(pattern.key(), route);
        List<Route> inNamespace = namespaces.computeIfAbsent(route.namespace(), ignored -> new ArrayList<>());
        int at = inNamespace.indexOf(existing);
        if (at >= 0) {
            inNamespace.set(at, route);
        } else {
            inNamespace.add(route);
        }
    }

    /** Every route in registration order. */
    public List<Route> routes() {
        return List.copyOf(routes.values());
    }

    /** The namespaces in registration order; the empty namespace (the root index) is not one. */
    public List<String> namespaces() {
        List<String> names = new ArrayList<>();
        for (String name : namespaces.keySet()) {
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    /** The route, endpoint and groups serving {@code method} on {@code path}, as WordPress picks them. */
    public Optional<Match> match(String path, String method) {
        if (tooLong(path)) {
            return Optional.empty();
        }
        for (Route route : candidates(path)) {
            Optional<Map<String, String>> groups = route.pattern().match(path);
            if (groups.isEmpty()) {
                continue;
            }
            for (Endpoint endpoint : route.endpoints()) {
                String checked = method.equals("HEAD") && !endpoint.declares("HEAD") ? "GET" : method;
                if (endpoint.declares(checked)) {
                    return Optional.of(new Match(route, endpoint, groups.get()));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The first route, in registration order across all namespaces, whose pattern matches {@code path} whatever the
     * method, as WordPress finds the route an OPTIONS request describes.
     */
    public Optional<MatchedRoute> firstMatching(String path) {
        if (tooLong(path)) {
            return Optional.empty();
        }
        for (Route route : routes()) {
            Optional<Map<String, String>> groups = route.pattern().match(path);
            if (groups.isPresent()) {
                return Optional.of(new MatchedRoute(route, groups.get()));
            }
        }
        return Optional.empty();
    }

    /** Whether {@code path} is longer than {@link #MAX_ROUTE_BYTES} in UTF-8; stops counting at the bound. */
    static boolean tooLong(String path) {
        if (path.length() > MAX_ROUTE_BYTES) {
            return true;
        }
        int bytes = 0;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c < 0x80) {
                bytes++;
            } else if (c < 0x800 || Character.isSurrogate(c)) {
                // each half of a surrogate pair counts 2, so the pair counts its 4 bytes
                bytes += 2;
            } else {
                bytes += 3;
            }
            if (bytes > MAX_ROUTE_BYTES) {
                return true;
            }
        }
        return false;
    }

    private List<Route> candidates(String path) {
        int start = 0;
        while (start < path.length() && path.charAt(start) == '/') {
            start++;
        }
        String trimmed = path.substring(start);
        String prefixed = trimmed.endsWith("/") ? trimmed : trimmed + "/";
        List<Route> first = new ArrayList<>();
        for (Map.Entry<String, List<Route>> namespace : namespaces.entrySet()) {
            if (!namespace.getKey().isEmpty() && prefixed.startsWith(namespace.getKey())) {
                first.addAll(namespace.getValue());
            }
        }
        return first.isEmpty() ? List.copyOf(routes.values()) : first;
    }
}
