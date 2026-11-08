package org.quarkpress.restwp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.common.annotation.Blocking;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jboss.resteasy.reactive.common.headers.HeaderUtil;
import org.jboss.resteasy.reactive.server.multipart.FileItem;
import org.jboss.resteasy.reactive.server.multipart.FormValue;
import org.jboss.resteasy.reactive.server.multipart.MultipartFormDataInput;
import org.quarkpress.restwp.UploadedFile;

/**
 * The one JAX-RS resource of the REST API. Only requests the {@link RestRequestFilter} re-routed here are served; a
 * request that reaches the path any other way is answered 404 with an empty body.
 */
@Path(RestEntryResource.PATH)
@Blocking
public class RestEntryResource {

    /** The internal path REST requests are re-routed to. */
    public static final String PATH = "/_quarkpress/rest-wp-v2";

    /** Below {@link #PATH}, where a request with a multipart body is re-routed. */
    static final String MULTIPART_PATH = "/multipart";

    @Inject
    RestServer server;

    /**
     * A request the filter re-routed (always as a POST), with its body: a JSON, form or untyped body is read whole
     * (within Quarkus's body limit), any other is passed on unread, as a stream the handler reads.
     */
    @POST
    @Path("{any: .*}")
    public Response post(@Context RoutingContext context, InputStream body) throws IOException {
        String contentType = context.request().getHeader("Content-Type");
        if (body == null) {
            return serve(context, new byte[0], Optional.empty(), List.of(), null);
        }
        if (contentType == null || IncomingRequest.isJson(contentType) || IncomingRequest.isForm(contentType)) {
            return serve(context, body.readAllBytes(), Optional.empty(), List.of(), null);
        }
        return serve(context, new byte[0], Optional.of(body), List.of(), null);
    }

    /**
     * A re-routed multipart request: for a POST (the method the client sent), the text parts are its form fields, added
     * as PHP adds them, and the file parts its files; any other method gets neither, as PHP reads a multipart body only
     * for POST. Quarkus has parsed the body, storing the file parts on disk until the request ends; it hands the parts
     * over grouped by name, each name where it first appears and its parts in body order.
     */
    @POST
    @Path(MULTIPART_PATH)
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response postMultipart(@Context RoutingContext context, MultipartFormDataInput input) throws IOException {
        if (!(context.get(RestRequestFilter.ENTRY) instanceof RestRequestFilter.Entry entry)
                || !entry.method().equals("POST")) {
            return serve(context, new byte[0], Optional.empty(), List.of(), RestJson.NODES.objectNode());
        }
        ObjectNode form = RestJson.NODES.objectNode();
        List<UploadedFile> files = new ArrayList<>();
        for (Map.Entry<String, Collection<FormValue>> field : input.getValues().entrySet()) {
            String name = field.getKey();
            if (name == null) {
                continue;
            }
            for (FormValue value : field.getValue()) {
                if (sentAsFile(value) && (value.getFileName() == null || !value.isFileItem())) {
                    // a file part without a usable file name (empty, or a directory alone): no file was uploaded
                    continue;
                }
                if (value.getFileName() != null) {
                    FileItem item = value.getFileItem();
                    files.add(new UploadedFile(
                            name,
                            value.getFileName(),
                            Optional.ofNullable(header(value, "Content-Type")),
                            item.getFile(),
                            item.getFileSize()));
                } else if (value.isFileItem()) {
                    // a part of a binary content type without a file name: a form field all the same
                    PhpQuery.add(form, name, new String(readAll(value.getFileItem()), StandardCharsets.UTF_8));
                } else {
                    PhpQuery.add(form, name, value.getValue());
                }
            }
        }
        return serve(context, new byte[0], Optional.empty(), files, form);
    }

    /** Whether a part's {@code Content-Disposition} names a file name, even an empty one. */
    private static boolean sentAsFile(FormValue value) {
        String disposition = header(value, "Content-Disposition");
        return disposition != null
                && HeaderUtil.extractQuotedValueFromHeaderWithEncoding(disposition, "filename") != null;
    }

    private static String header(FormValue value, String name) {
        return value.getHeaders() == null ? null : value.getHeaders().getFirst(name);
    }

    private static byte[] readAll(FileItem item) throws IOException {
        try (InputStream in = item.getInputStream()) {
            return in.readAllBytes();
        }
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

    /**
     * Serves a re-routed request; {@code multipartForm} is the form fields of a multipart body, null for any other body
     * (whose form fields, if any, come from {@code body}).
     */
    private Response serve(
            RoutingContext context,
            byte[] body,
            Optional<InputStream> stream,
            List<UploadedFile> files,
            ObjectNode multipartForm) {
        if (!(context.get(RestRequestFilter.ENTRY) instanceof RestRequestFilter.Entry entry)) {
            return Response.status(404).build();
        }
        ObjectNode form = multipartForm != null
                ? multipartForm
                : form(entry, context.request().getHeader("Content-Type"), body);
        IncomingRequest request = new IncomingRequest(
                entry.method(),
                "/",
                entry.query(),
                headers(context),
                body,
                form,
                entry.target(),
                secure(context),
                files,
                stream);
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

    /**
     * Whether the request came over HTTPS: its scheme as Vert.x reports it, which is the forwarded one when Quarkus's
     * proxy settings honour {@code X-Forwarded-Proto} or {@code Forwarded}.
     */
    static boolean secure(RoutingContext context) {
        return context.request().isSSL()
                || "https".equalsIgnoreCase(context.request().scheme());
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
