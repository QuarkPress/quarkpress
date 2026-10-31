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
        ObjectNode form = form(entry, context.request().getHeader("Content-Type"), body);
        IncomingRequest request =
                new IncomingRequest(entry.method(), "/", entry.query(), headers(context), body, form, entry.target());
        return switch (route(entry, form)) {
            case Decision.Route route -> response(server.serve(request.withRoute(route.route())));
            case Decision.Refused refused -> response(server.refuse(request, refused.code(), refused.message()));
            case Decision.NotRest notRest -> Response.status(404).build();
        };
    }

    /** What a REST request's route is, once its form fields are known. */
    sealed interface Decision {

        /** The route to serve. */
        record Route(String route) implements Decision {}

        /** Refused before the REST server runs, with WordPress's code and message. */
        record Refused(String code, String message) implements Decision {}

        /** Not a REST request after all ({@code rest_route} empty to PHP, or no route at all). */
        record NotRest() implements Decision {}
    }

    /**
     * The form fields PHP reads for a request: those of a POST with the form content type, none otherwise.
     */
    static ObjectNode form(RestRequestFilter.Entry entry, String contentType, byte[] body) {
        return entry.method().equals("POST") && IncomingRequest.isForm(contentType)
                ? PhpQuery.parse(new String(body, StandardCharsets.ISO_8859_1))
                : RestJson.NODES.objectNode();
    }

    /**
     * The route a REST request asks for: {@code rest_route} from the form (when the form may carry it) or else the
     * query, which must agree when both have one and must be a string; else the route its path addresses.
     */
    static Decision route(RestRequestFilter.Entry entry, ObjectNode form) {
        JsonNode fromQuery = entry.query().get("rest_route");
        JsonNode fromForm = entry.form() ? form.get("rest_route") : null;
        if (fromQuery != null && fromForm != null && !fromQuery.equals(fromForm)) {
            return new Decision.Refused("wp_die", "A variable mismatch has been detected.");
        }
        JsonNode chosen = fromForm != null ? fromForm : fromQuery;
        if (chosen != null) {
            if (!chosen.isTextual()) {
                return new Decision.Refused("rest_path_invalid_type", "The REST route parameter must be a string.");
            }
            if (RestRequestFilter.phpEmpty(chosen)) {
                return new Decision.NotRest();
            }
            return new Decision.Route(RestPaths.trimRoute(chosen.textValue()));
        }
        if (entry.pathRoute() != null) {
            return new Decision.Route(RestPaths.trimRoute(PhpQuery.rawDecode(entry.pathRoute())));
        }
        return new Decision.NotRest();
    }

    static Map<String, List<String>> headers(RoutingContext context) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (Map.Entry<String, String> header : context.request().headers()) {
            headers.computeIfAbsent(header.getKey(), ignored -> new ArrayList<>())
                    .add(header.getValue());
        }
        return headers;
    }

    private static Response response(ServedResponse answer) {
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
        if (type == null && answer.body().length == 0) {
            // an empty answer, such as a redirect, has no content type
            return builder.build();
        }
        return builder.entity(answer.body()).type(type).build();
    }
}
