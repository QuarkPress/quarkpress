package org.quarkpress.restwp.deployment;

import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.OptimisticLockException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.quarkpress.core.CapabilityDeniedException;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteFeatureDisabledException;
import org.quarkpress.core.TermId;
import org.quarkpress.posts.PostException;
import org.quarkpress.restwp.Arg;
import org.quarkpress.restwp.Endpoint;
import org.quarkpress.restwp.Fields;
import org.quarkpress.restwp.Handler;
import org.quarkpress.restwp.Links;
import org.quarkpress.restwp.Paging;
import org.quarkpress.restwp.Permission;
import org.quarkpress.restwp.PermissionResult;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestException;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.RestResponse;
import org.quarkpress.restwp.RestRouteProvider;
import org.quarkpress.restwp.RestUrls;
import org.quarkpress.restwp.RouteRegistry;
import org.quarkpress.restwp.runtime.CollectionItems;
import org.quarkpress.restwp.runtime.RestJson;
import org.quarkpress.revisions.RevisionException;
import org.quarkpress.security.Capabilities;
import org.quarkpress.taxonomy.TermException;

/**
 * Routes of namespace {@code qp-test/v1} for the routing tests: {@code /echo/(?P<id>[\d]+)} (GET and POST) answers
 * {@code {"method", "id", "urlId", "params", "site", "root", "link", "home"}}; {@code /items} (GET) answers
 * {@code [{"id":1},{"id":2}]} with {@code X-WP-Total: 2} and {@code X-WP-TotalPages: 1}; {@code /headers} (GET)
 * answers {@code []} with {@code Last-Modified} and a lower-case {@code link}; {@code /nan} (GET) answers a body that
 * cannot be encoded;
 * {@code /site} (GET) answers {@code {"site"}}; {@code /guarded} answers {@code {"method", "per_page", "order"}} to GET
 * (args {@code per_page} 1 to 100, validated, and {@code order} asc or desc, sanitized; the caller needs
 * {@code edit_posts}) and to DELETE (the caller needs {@code delete_others_posts}); {@code /denied} (GET) refuses with
 * {@code rest_cannot_view}; {@code /throws/(?P<kind>[\w-]+)} (GET) throws the exception {@code kind} names;
 * {@code /json} (GET and POST) answers {@code {"a"}}, the parameter {@code a}; {@code /things} answers GET (public,
 * WordPress's collection args, batching allowed) with a page of {@link #THINGS} things and their pagination headers,
 * and POST (the caller needs {@code manage_options}); {@code /things/(?P<id>[\d]+)} answers {@code {"method", "id"}}
 * (an integer id when {@code _fields} selects it, which it always does, and a self link when it selects
 * {@code _links}) to GET (public), POST, PUT and PATCH (one endpoint; the caller needs {@code edit_posts}) and DELETE
 * (the caller needs {@code manage_options}); {@code /offset-things} (GET) is {@code /things} with WordPress's
 * {@code offset} arg; {@code /hidden} (GET, public) is not shown in the index; {@code /nodes/(?P<id>[\d]+)} (GET)
 * answers {@code {"id", "context"}} with the links {@link #nodeLinks} gives (when {@code _links} or {@code _embedded}
 * is selected), counting its runs in {@link #NODE_CALLS}, and refuses node 9 to an anonymous caller;
 * {@code /nodes} (GET, WordPress's collection args) lists nodes 3 and 4 as a WordPress collection does (each item's
 * links inlined); {@code /sizes} (GET, WordPress's collection args) answers {@code [{"per_page", "context"}]};
 * {@code /linker} (GET) answers {@code {"id":0}} with one embeddable link per query parameter not starting with
 * {@code _}: the parameter's name is the relation, its value the href.
 */
@ApplicationScoped
public class TestRoutes implements RestRouteProvider {

    /** The body of {@code /items}. */
    static final String ITEMS = "[{\"id\":1},{\"id\":2}]";

    /** The {@code Last-Modified} of {@code /headers}. */
    static final String LAST_MODIFIED = "Wed, 21 Oct 2026 07:28:00 GMT";

    /** The {@code link} (lower-case) of {@code /headers}. */
    static final String NEXT_LINK = "<http://localhost/wp-json/qp-test/v1/headers?page=2>; rel=\"next\"";

    /** How many things {@code /things} lists. */
    static final int THINGS = 3;

    /** The item schema of {@code /things}. */
    static final String THING_SCHEMA = "{\"title\":\"thing\",\"type\":\"object\",\"properties\":{\"id\":"
            + "{\"description\":\"Unique identifier for the thing.\",\"type\":\"integer\","
            + "\"context\":[\"view\",\"embed\",\"edit\"],\"readonly\":true}}}";

    /** The item schema of {@code /nodes}. */
    static final String NODE_SCHEMA = "{\"title\":\"node\",\"type\":\"object\",\"properties\":{\"id\":"
            + "{\"description\":\"Unique identifier for the node.\",\"type\":\"integer\","
            + "\"context\":[\"view\",\"embed\",\"edit\"],\"readonly\":true},\"context\":"
            + "{\"description\":\"The context asked for.\",\"type\":\"string\","
            + "\"context\":[\"view\",\"embed\",\"edit\"],\"readonly\":true}}}";

    /** How many times the node handler ran, by node id. */
    static final Map<String, AtomicInteger> NODE_CALLS = new ConcurrentHashMap<>();

    /** The item schema the nodes' handler selects fields from. */
    private static final ObjectNode NODE = schema(NODE_SCHEMA);

    /** The item schema the things' handler selects fields from (one instance, so a request selects once). */
    private static final ObjectNode THING = thingSchema();

    @Inject
    SiteContext site;

    @Inject
    RestUrls urls;

    @Inject
    Capabilities capabilities;

    @Override
    public void register(RouteRegistry routes) {
        Handler echo = this::echo;
        routes.route("qp-test/v1", "/echo/(?P<id>[\\d]+)")
                .endpoint(Endpoint.of("GET").handler(echo).build())
                .endpoint(Endpoint.of("POST").handler(echo).build())
                .register();
        routes.route("qp-test/v1", "/items")
                .endpoint(Endpoint.of("GET").handler(TestRoutes::items).build())
                .register();
        routes.route("qp-test/v1", "/headers")
                .endpoint(Endpoint.of("GET")
                        .handler(request -> RestResponse.ok(RestJson.NODES.arrayNode())
                                .withHeader("Last-Modified", LAST_MODIFIED)
                                .withHeader("link", NEXT_LINK))
                        .build())
                .register();
        routes.route("qp-test/v1", "/nan")
                .endpoint(Endpoint.of("GET")
                        .handler(request ->
                                RestResponse.ok(RestJson.NODES.objectNode().put("x", Double.NaN)))
                        .build())
                .register();
        routes.route("qp-test/v1", "/site")
                .endpoint(Endpoint.of("GET")
                        .handler(request -> RestResponse.ok(RestJson.NODES
                                .objectNode()
                                .put("site", site.siteId().value())))
                        .build())
                .register();
        Handler guarded = request -> {
            ObjectNode body = RestJson.NODES.objectNode();
            body.put("method", request.method());
            body.set("per_page", request.param("per_page").orElse(NullNode.getInstance()));
            body.set("order", request.param("order").orElse(NullNode.getInstance()));
            return RestResponse.ok(body);
        };
        routes.route("qp-test/v1", "/guarded")
                .endpoint(Endpoint.of("GET")
                        .arg(Arg.of("per_page", "integer")
                                .minimum(1)
                                .maximum(100)
                                .build())
                        .arg(Arg.of("order", "string")
                                .enumOf("asc", "desc")
                                .phase(Arg.Phase.SANITIZE)
                                .build())
                        .permission(can("edit_posts"))
                        .handler(guarded)
                        .build())
                .endpoint(Endpoint.of("DELETE")
                        .permission(can("delete_others_posts"))
                        .handler(guarded)
                        .build())
                .register();
        routes.route("qp-test/v1", "/denied")
                .endpoint(Endpoint.of("GET")
                        .permission(request ->
                                PermissionResult.denied(RestError.of("rest_cannot_view", "Nope.", request.authCode())))
                        .handler(request -> RestResponse.ok(RestJson.NODES.objectNode()))
                        .build())
                .register();
        routes.route("qp-test/v1", "/throws/(?P<kind>[\\w-]+)")
                .endpoint(Endpoint.of("GET").handler(TestRoutes::fail).build())
                .register();
        Handler json = request -> RestResponse.ok(
                RestJson.NODES.objectNode().set("a", request.param("a").orElse(NullNode.getInstance())));
        routes.route("qp-test/v1", "/json")
                .endpoint(Endpoint.of("GET").handler(json).build())
                .endpoint(Endpoint.of("POST").handler(json).build())
                .register();
        ObjectNode thing = thingSchema();
        routes.route("qp-test/v1", "/things")
                .schema(TestRoutes::thingSchema)
                .endpoint(Endpoint.of("GET")
                        .args(Paging.collectionArgs(thing, false))
                        .allowBatch(true)
                        .handler(TestRoutes::things)
                        .build())
                .endpoint(Endpoint.of("POST")
                        .permission(can("manage_options"))
                        .allowBatch(true)
                        .handler(this::thing)
                        .build())
                .register();
        routes.route("qp-test/v1", "/offset-things")
                .schema(TestRoutes::thingSchema)
                .endpoint(Endpoint.of("GET")
                        .args(Paging.collectionArgs(thing, false))
                        .arg(Paging.offsetArg())
                        .handler(TestRoutes::things)
                        .build())
                .register();
        routes.route("qp-test/v1", "/hidden")
                .endpoint(Endpoint.of("GET")
                        .showInIndex(false)
                        .handler(this::thing)
                        .build())
                .register();
        routes.route("qp-test/v1", "/things/(?P<id>[\\d]+)")
                .schema(TestRoutes::thingSchema)
                .endpoint(Endpoint.of("GET").handler(this::thing).build())
                .endpoint(Endpoint.of("POST", "PUT", "PATCH")
                        .permission(can("edit_posts"))
                        .handler(this::thing)
                        .build())
                .endpoint(Endpoint.of("DELETE")
                        .permission(can("manage_options"))
                        .handler(this::thing)
                        .build())
                .register();
        ObjectNode node = schema(NODE_SCHEMA);
        routes.route("qp-test/v1", "/nodes")
                .schema(() -> schema(NODE_SCHEMA))
                .endpoint(Endpoint.of("GET")
                        .args(Paging.collectionArgs(node, false))
                        .handler(request -> {
                            var items = RestJson.NODES.arrayNode();
                            items.add(CollectionItems.inline(node(request, "3"), request));
                            items.add(CollectionItems.inline(node(request, "4"), request));
                            return RestResponse.ok(items);
                        })
                        .build())
                .register();
        routes.route("qp-test/v1", "/linker")
                .endpoint(Endpoint.of("GET")
                        .handler(request -> {
                            Links links = Links.none();
                            for (var param : request.queryParams().properties()) {
                                if (!param.getKey().startsWith("_")
                                        && param.getValue().isTextual()) {
                                    links = links.add(
                                            param.getKey(),
                                            param.getValue().textValue(),
                                            Map.of("embeddable", com.fasterxml.jackson.databind.node.BooleanNode.TRUE));
                                }
                            }
                            return RestResponse.ok(RestJson.NODES.objectNode().put("id", 0))
                                    .withLinks(links);
                        })
                        .build())
                .register();
        routes.route("qp-test/v1", "/sizes")
                .schema(() -> schema(NODE_SCHEMA))
                .endpoint(Endpoint.of("GET")
                        .args(Paging.collectionArgs(node, false))
                        .handler(request -> {
                            var items = RestJson.NODES.arrayNode();
                            items.addObject()
                                    .put("per_page", Paging.of(request).perPage())
                                    .put("context", request.context());
                            return RestResponse.ok(items);
                        })
                        .build())
                .register();
        routes.route("qp-test/v1", "/nodes/(?P<id>[\\d]+)")
                .schema(() -> schema(NODE_SCHEMA))
                .endpoint(Endpoint.of("GET")
                        .permission(request -> request.urlParams().get("id").equals("9")
                                        && request.user().isEmpty()
                                ? PermissionResult.forbidden(request)
                                : PermissionResult.allowed())
                        .handler(request -> {
                            String id = request.urlParams().get("id");
                            NODE_CALLS
                                    .computeIfAbsent(id, key -> new AtomicInteger())
                                    .incrementAndGet();
                            return node(request, id);
                        })
                        .build())
                .register();
    }

    /**
     * Node {@code id} as a WordPress controller builds it: {@code id} and {@code context} as selected, and its links
     * when {@code _links} or {@code _embedded} is.
     */
    private RestResponse node(RestRequest request, String id) {
        Fields.Selection fields = Fields.forResponse(request, NODE);
        ObjectNode body = RestJson.NODES.objectNode();
        if (fields.includes("id")) {
            body.put("id", Long.parseLong(id));
        }
        if (fields.includes("context")) {
            body.put("context", request.context());
        }
        RestResponse response = RestResponse.ok(body);
        if (fields.includes("_links") || fields.includes("_embedded")) {
            response = response.withLinks(nodeLinks(id));
        }
        return response;
    }

    /**
     * The links of node {@code id}: {@code self} always; node 1: {@code up} (embeddable) to node 2; node 3: {@code up}
     * to node 1 (embeddable) and to node 2 (not embeddable), {@code author} (not embeddable) to node 7, and
     * {@code related} (embeddable) to node 1 again; node 4: {@code up} (embeddable) to node 9; node 5:
     * {@code https://api.w.org/sizes} (embeddable) to {@code /sizes} plain, with {@code per_page=2} and with
     * {@code context=view}, {@code elsewhere} (embeddable) to node 1 on another host and through another host's
     * {@code rest_route}, and {@code local} (embeddable) to node 1 through this site's {@code rest_route}.
     */
    Links nodeLinks(String id) {
        Map<String, com.fasterxml.jackson.databind.JsonNode> embeddable =
                Map.of("embeddable", com.fasterxml.jackson.databind.node.BooleanNode.TRUE);
        Links links = Links.none().add("self", urls.route("/qp-test/v1/nodes/" + id));
        switch (id) {
            case "1" -> links = links.add("up", urls.route("/qp-test/v1/nodes/2"), embeddable);
            case "3" ->
                links = links.add("up", urls.route("/qp-test/v1/nodes/1"), embeddable)
                        .add("up", urls.route("/qp-test/v1/nodes/2"))
                        .add("author", urls.route("/qp-test/v1/nodes/7"))
                        .add("related", urls.route("/qp-test/v1/nodes/1"), embeddable);
            case "4" -> links = links.add("up", urls.route("/qp-test/v1/nodes/9"), embeddable);
            case "5" ->
                links = links.add("https://api.w.org/sizes", urls.route("/qp-test/v1/sizes"), embeddable)
                        .add(
                                "https://api.w.org/sizes",
                                urls.route("/qp-test/v1/sizes", Map.of("per_page", "2")),
                                embeddable)
                        .add(
                                "https://api.w.org/sizes",
                                urls.route("/qp-test/v1/sizes", Map.of("context", "view")),
                                embeddable)
                        .add("elsewhere", "http://elsewhere.example/wp-json/qp-test/v1/nodes/1", embeddable)
                        .add("elsewhere", "http://elsewhere.example/?rest_route=/qp-test/v1/nodes/1", embeddable)
                        .add("local", urls.home() + "?rest_route=/qp-test/v1/nodes/1", embeddable);
            default -> {}
        }
        return links;
    }

    static ObjectNode thingSchema() {
        return schema(THING_SCHEMA);
    }

    static ObjectNode schema(String text) {
        try {
            return (ObjectNode) RestJson.READER.readTree(text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A page of the things {@code 1..THINGS}, with WordPress's pagination headers. */
    private static RestResponse things(RestRequest request) {
        Paging paging = Paging.of(request);
        var page = RestJson.NODES.arrayNode();
        for (long id = paging.offset() + 1L; id <= THINGS && id <= (long) paging.offset() + paging.perPage(); id++) {
            page.addObject().put("id", id);
        }
        return RestResponse.paged(page, THINGS, paging, request);
    }

    /**
     * A thing as a WordPress controller builds it: {@code method} always (it is not in the schema, so only the server's
     * reduction removes it), {@code id} when {@link Fields#forResponse} selects it (always, as it is in the schema),
     * and a {@code self} link only when {@code _links} or {@code _embedded} is selected.
     */
    private RestResponse thing(RestRequest request) {
        Fields.Selection fields = Fields.forResponse(request, THING);
        String id = request.urlParams().get("id");
        ObjectNode body = RestJson.NODES.objectNode();
        body.put("method", request.method());
        if (fields.includes("id")) {
            if (id == null) {
                body.putNull("id");
            } else {
                body.put("id", Long.parseLong(id));
            }
        }
        RestResponse response = RestResponse.ok(body);
        if (id != null && (fields.includes("_links") || fields.includes("_embedded"))) {
            response = response.withLinks(Links.none().add("self", urls.route("/qp-test/v1/things/" + id)));
        }
        return response;
    }

    private Permission can(String capability) {
        return request -> capabilities.can(request.identity(), capability, Optional.empty())
                ? PermissionResult.allowed()
                : PermissionResult.forbidden(request);
    }

    private static RestResponse fail(RestRequest request) {
        String kind = request.urlParams().get("kind");
        if (kind.equals("stack-overflow")) {
            throw new StackOverflowError();
        }
        throw switch (kind) {
            case "forbidden" -> new ForbiddenException();
            case "unauthorized" -> new UnauthorizedException();
            case "capability-denied" -> new CapabilityDeniedException("manage_network");
            case "capability-denied-anonymous" -> new CapabilityDeniedException("manage_network", true);
            case "feature-disabled" -> new SiteFeatureDisabledException("qp-test");
            case "duplicate-term-slug" -> TermException.duplicateSlug("news");
            case "term-exists" -> TermException.exists(new TermId(7), false);
            case "invalid-post" -> PostException.invalidPost(PostId.of(9));
            case "invalid-revision" -> RevisionException.invalidRevision();
            case "stale-version" -> new OptimisticLockException("stale");
            case "wrapped-stale-version" -> new IllegalStateException(new OptimisticLockException("stale"));
            case "rest" -> new RestException(RestError.of("rest_custom", "Custom.", 418));
            default -> new IllegalStateException("unexpected failure " + kind);
        };
    }

    /** Two items, with WordPress's collection totals. */
    private static RestResponse items(RestRequest request) {
        var items = RestJson.NODES.arrayNode();
        items.addObject().put("id", 1);
        items.addObject().put("id", 2);
        return RestResponse.ok(items).withHeader("X-WP-Total", "2").withHeader("X-WP-TotalPages", "1");
    }

    private RestResponse echo(RestRequest request) {
        ObjectNode body = RestJson.NODES.objectNode();
        body.put("method", request.method());
        body.set("id", request.param("id").orElse(NullNode.getInstance()));
        body.put("urlId", request.urlParams().get("id"));
        body.set("params", request.queryParams());
        body.put("site", site.isResolved() ? site.siteId().value() : 0);
        body.put("root", urls.root());
        body.put("link", urls.route("/qp-test/v1/items", Map.of("a b", "c&d/" + new String(Character.toChars(0xE9)))));
        body.put("home", urls.home());
        return RestResponse.ok(body);
    }

    /** A plain resource of the application whose path holds the REST base below another prefix. */
    @Path("/it-plain/x/wp-json/ping")
    public static class PlainResource {

        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String ping() {
            return "pong";
        }
    }
}
