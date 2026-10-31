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
import java.util.Map;
import java.util.Optional;
import org.quarkpress.core.CapabilityDeniedException;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteFeatureDisabledException;
import org.quarkpress.core.TermId;
import org.quarkpress.posts.PostException;
import org.quarkpress.restwp.Arg;
import org.quarkpress.restwp.Endpoint;
import org.quarkpress.restwp.Handler;
import org.quarkpress.restwp.Permission;
import org.quarkpress.restwp.PermissionResult;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestException;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.RestResponse;
import org.quarkpress.restwp.RestRouteProvider;
import org.quarkpress.restwp.RestUrls;
import org.quarkpress.restwp.RouteRegistry;
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
 * {@code /json} (GET and POST) answers {@code {"a"}}, the parameter {@code a}.
 */
@ApplicationScoped
public class TestRoutes implements RestRouteProvider {

    /** The body of {@code /items}. */
    static final String ITEMS = "[{\"id\":1},{\"id\":2}]";

    /** The {@code Last-Modified} of {@code /headers}. */
    static final String LAST_MODIFIED = "Wed, 21 Oct 2026 07:28:00 GMT";

    /** The {@code link} (lower-case) of {@code /headers}. */
    static final String NEXT_LINK = "<http://localhost/wp-json/qp-test/v1/headers?page=2>; rel=\"next\"";

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
