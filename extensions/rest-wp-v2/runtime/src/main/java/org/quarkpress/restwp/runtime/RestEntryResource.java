package org.quarkpress.restwp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.common.annotation.Blocking;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one JAX-RS resource of the REST API. Only requests the {@link RestRequestFilter} re-routed here are served; a
 * request that reaches the path any other way is answered 404 with an empty body.
 */
@Path(RestEntryResource.PATH)
@Blocking
public class RestEntryResource {

    /** The internal path REST requests are re-routed to. */
    public static final String PATH = "/_quarkpress/rest-wp-v2";

    private static final String HTML = "text/html; charset=UTF-8";

    @Inject
    RestServer server;

    /** A request the filter re-routed (always as a POST), with its body. */
    @POST
    @Path("{any: .*}")
    public Response post(@Context RoutingContext context, byte[] body) {
        return serve(context, body);
    }

    // Only direct requests reach the other methods: the filter re-routes every REST request as a POST.

    @GET
    @Path("{any: .*}")
    public Response get() {
        return Response.status(404).build();
    }

    @PUT
    @Path("{any: .*}")
    public Response put() {
        return Response.status(404).build();
    }

    @PATCH
    @Path("{any: .*}")
    public Response patch() {
        return Response.status(404).build();
    }

    @DELETE
    @Path("{any: .*}")
    public Response delete() {
        return Response.status(404).build();
    }

    @HEAD
    @Path("{any: .*}")
    public Response head() {
        return Response.status(404).build();
    }

    @OPTIONS
    @Path("{any: .*}")
    public Response options() {
        return Response.status(404).build();
    }

    private Response serve(RoutingContext context, byte[] rawBody) {
        if (!(context.get(RestRequestFilter.ENTRY) instanceof RestRequestFilter.Entry entry)) {
            return Response.status(404).build();
        }
        byte[] body = rawBody == null ? new byte[0] : rawBody;
        String method = entry.method();
        String contentType = context.request().getHeader("Content-Type");
        ObjectNode form = method.equals("POST") && IncomingRequest.isForm(contentType)
                ? PhpQuery.parse(new String(body, StandardCharsets.ISO_8859_1))
                : RestJson.NODES.objectNode();
        ObjectNode query = entry.query();
        JsonNode fromQuery = query.get("rest_route");
        JsonNode fromForm = entry.form() ? form.get("rest_route") : null;
        if (fromQuery != null && fromForm != null && !fromQuery.equals(fromForm)) {
            return died("A variable mismatch has been detected.");
        }
        JsonNode chosen = fromForm != null ? fromForm : fromQuery;
        String route;
        if (chosen != null) {
            if (!chosen.isTextual()) {
                return died("The REST route parameter must be a string.");
            }
            if (RestRequestFilter.phpEmpty(chosen)) {
                return Response.status(404).build();
            }
            route = RestPaths.trimRoute(chosen.textValue());
        } else if (entry.pathRoute() != null) {
            route = RestPaths.trimRoute(PhpQuery.rawDecode(entry.pathRoute()));
        } else {
            return Response.status(404).build();
        }
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (Map.Entry<String, String> header : context.request().headers()) {
            headers.computeIfAbsent(header.getKey(), ignored -> new ArrayList<>())
                    .add(header.getValue());
        }
        RestServer.Answer answer = server.serve(new IncomingRequest(method, route, query, headers, body, form));
        return response(answer);
    }

    private static Response response(RestServer.Answer answer) {
        Response.ResponseBuilder builder = Response.status(answer.status());
        String type = null;
        for (Map.Entry<String, List<String>> header : answer.headers().entrySet()) {
            if (header.getKey().equalsIgnoreCase("Content-Type")) {
                type = header.getValue().isEmpty() ? null : header.getValue().get(0);
                continue;
            }
            for (String value : header.getValue()) {
                builder.header(header.getKey(), value);
            }
        }
        return builder.entity(answer.body()).type(type).build();
    }

    /**
     * WordPress's wp_die page for a request it refuses before the REST server starts: 400, HTML, no-cache, no REST
     * headers.
     */
    static Response died(String message) {
        String page = "<!DOCTYPE html>\n<html lang=\"en-US\">\n<head>\n"
                + "\t<meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\" />\n"
                + "\t<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
                + "\t<meta name='robots' content='noindex, follow' />\n"
                + "\t<title>Error</title>\n</head>\n<body id=\"error-page\">\n"
                + "\t<div class=\"wp-die-message\">" + message + "</div></body>\n</html>\n";
        return Response.status(400)
                .header("Expires", "Wed, 11 Jan 1984 05:00:00 GMT")
                .header("Cache-Control", "no-cache, must-revalidate, max-age=0, no-store, private")
                .entity(page.getBytes(StandardCharsets.UTF_8))
                .type(HTML)
                .build();
    }
}
