package org.quarkpress.restwp.deployment;

import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;
import org.quarkpress.core.SiteContext;
import org.quarkpress.restwp.Endpoint;
import org.quarkpress.restwp.Handler;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.RestResponse;
import org.quarkpress.restwp.RestRouteProvider;
import org.quarkpress.restwp.RestUrls;
import org.quarkpress.restwp.RouteRegistry;
import org.quarkpress.restwp.runtime.RestJson;

/**
 * Routes of namespace {@code qp-test/v1} for the routing tests: {@code /echo/(?P<id>[\d]+)} (GET and POST) answers
 * {@code {"method", "id", "urlId", "params", "site", "root", "link", "home"}}; {@code /items} (GET) answers {@code []}.
 */
@ApplicationScoped
public class TestRoutes implements RestRouteProvider {

    @Inject
    SiteContext site;

    @Inject
    RestUrls urls;

    @Override
    public void register(RouteRegistry routes) {
        Handler echo = this::echo;
        routes.route("qp-test/v1", "/echo/(?P<id>[\\d]+)")
                .endpoint(Endpoint.of("GET").handler(echo).build())
                .endpoint(Endpoint.of("POST").handler(echo).build())
                .register();
        routes.route("qp-test/v1", "/items")
                .endpoint(Endpoint.of("GET")
                        .handler(request -> RestResponse.ok(RestJson.NODES.arrayNode()))
                        .build())
                .register();
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
