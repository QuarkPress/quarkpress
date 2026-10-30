package org.quarkpress.restwp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.quarkpress.core.UserId;
import org.quarkpress.restwp.Arg;
import org.quarkpress.restwp.PermissionResult;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestException;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.RestResponse;
import org.quarkpress.restwp.RestRouteProvider;
import org.quarkpress.security.QuarkPressPrincipal;

/**
 * Serves REST requests: applies the method override, matches the route, gathers the parameters by source, checks the
 * endpoint's permission, calls its handler and encodes the answer as PHP does.
 */
@ApplicationScoped
public class RestServer {

    /** What goes back over HTTP. */
    public record Answer(int status, Map<String, List<String>> headers, byte[] body) {}

    static final String JSON_TYPE = "application/json; charset=UTF-8";

    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    @Inject
    Instance<RestRouteProvider> providers;

    @Inject
    SecurityIdentity identity;

    private volatile RouteTable table;

    /** The routes, registered by every provider on first use. */
    public RouteTable table() {
        RouteTable current = table;
        if (current == null) {
            synchronized (this) {
                current = table;
                if (current == null) {
                    current = build();
                    table = current;
                }
            }
        }
        return current;
    }

    private RouteTable build() {
        List<RestRouteProvider> ordered = new ArrayList<>();
        providers.forEach(ordered::add);
        ordered.sort(Comparator.comparingInt(RestRouteProvider::order)
                .thenComparing(provider -> provider.getClass().getName()));
        RouteTable routes = new RouteTable();
        for (RestRouteProvider provider : ordered) {
            provider.register(routes.registry());
        }
        return routes;
    }

    public Answer serve(IncomingRequest incoming) {
        String method = method(incoming);
        Optional<RouteTable.Match> found = table().match(incoming.route(), method);
        if (found.isEmpty()) {
            return error(RestError.of("rest_no_route", "No route was found matching the URL and request method.", 404));
        }
        RouteTable.Match match = found.get();
        ParamSources sources = new ParamSources(
                jsonParams(incoming),
                bodyParams(incoming, method),
                incoming.query(),
                match.urlParams(),
                defaults(match));
        Served request = new Served(method, match, incoming.route(), sources, incoming.query(), identity);
        try {
            if (match.endpoint().permission().check(request) instanceof PermissionResult.Denied denied) {
                return error(denied.error());
            }
            return answer(match.endpoint().handler().handle(request));
        } catch (RestException refused) {
            return error(refused.error());
        }
    }

    /** The method after the override: the query's {@code _method}, else the override header, upper-cased. */
    static String method(IncomingRequest incoming) {
        JsonNode override = incoming.query().get("_method");
        if (override != null && override.isTextual()) {
            return upper(override.textValue());
        }
        String header = incoming.header("X-HTTP-Method-Override");
        if (header != null) {
            return upper(header);
        }
        return upper(incoming.method());
    }

    private static String upper(String text) {
        StringBuilder upper = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            upper.append(c >= 'a' && c <= 'z' ? (char) (c - 32) : c);
        }
        return upper.toString();
    }

    /** The JSON body's parameters: only with a JSON content type, a body PHP does not count as empty and no error. */
    static ObjectNode jsonParams(IncomingRequest incoming) {
        ObjectNode params = RestJson.NODES.objectNode();
        if (!IncomingRequest.isJson(incoming.contentType()) || incoming.emptyBody()) {
            return params;
        }
        if (PhpJsonCheck.check(incoming.body()).isPresent()) {
            return params;
        }
        JsonNode decoded;
        try {
            decoded = RestJson.READER.readTree(incoming.body());
        } catch (IOException beyondJacksonLimits) {
            return params;
        }
        if (decoded instanceof ObjectNode object) {
            return object;
        }
        if (decoded instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                params.set(Integer.toString(i), array.get(i));
            }
        }
        return params;
    }

    /** Why the JSON body cannot be decoded, when it has a JSON content type, is not empty and fails. */
    public static Optional<RestJsonFailure> jsonFailure(IncomingRequest incoming) {
        if (!IncomingRequest.isJson(incoming.contentType()) || incoming.emptyBody()) {
            return Optional.empty();
        }
        Optional<RestJsonFailure> failure = PhpJsonCheck.check(incoming.body());
        if (failure.isPresent()) {
            return failure;
        }
        try {
            RestJson.READER.readTree(incoming.body());
            return Optional.empty();
        } catch (IOException beyondJacksonLimits) {
            return Optional.of(RestJsonFailure.SYNTAX);
        }
    }

    /**
     * The form body's parameters, read only for POST, PUT, PATCH and DELETE: PHP's own form fields, under the body
     * parsed as a form when the method is not POST and the content type is absent or exactly the form one.
     */
    static ObjectNode bodyParams(IncomingRequest incoming, String method) {
        ObjectNode params = RestJson.NODES.objectNode();
        if (!BODY_METHODS.contains(method)) {
            return params;
        }
        if (!method.equals("POST") && !incoming.emptyBody() && IncomingRequest.isRawForm(incoming.contentType())) {
            params.setAll(PhpQuery.parse(new String(incoming.body(), StandardCharsets.ISO_8859_1)));
        }
        params.setAll(incoming.form());
        return params;
    }

    private static ObjectNode defaults(RouteTable.Match match) {
        ObjectNode defaults = RestJson.NODES.objectNode();
        for (Arg arg : match.endpoint().args()) {
            arg.defaultValue().ifPresent(value -> defaults.set(arg.name(), value.deepCopy()));
        }
        return defaults;
    }

    static Answer answer(RestResponse response) {
        Map<String, List<String>> headers = new LinkedHashMap<>(response.headers());
        headers.put("Content-Type", List.of(JSON_TYPE));
        return new Answer(response.status(), headers, RestJson.encode(response.body(), false));
    }

    static Answer error(RestError error) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Content-Type", List.of(JSON_TYPE));
        return new Answer(error.status(), headers, RestJson.encode(error.toJson(), false));
    }

    /** A matched request as handlers see it. */
    private static final class Served implements RestRequest {

        private final String method;
        private final RouteTable.Match match;
        private final String path;
        private final ParamSources sources;
        private final ObjectNode query;
        private final SecurityIdentity identity;

        Served(
                String method,
                RouteTable.Match match,
                String path,
                ParamSources sources,
                ObjectNode query,
                SecurityIdentity identity) {
            this.method = method;
            this.match = match;
            this.path = path;
            this.sources = sources;
            this.query = query;
            this.identity = identity;
        }

        @Override
        public String method() {
            return method;
        }

        @Override
        public String route() {
            return match.route().key();
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public Map<String, String> urlParams() {
            return match.urlParams();
        }

        @Override
        public Optional<JsonNode> param(String name) {
            return sources.get(name);
        }

        @Override
        public boolean hasParam(String name) {
            return sources.has(name);
        }

        @Override
        public ObjectNode queryParams() {
            return query;
        }

        @Override
        public String context() {
            return param("context")
                    .filter(JsonNode::isValueNode)
                    .map(JsonNode::asText)
                    .orElse("view");
        }

        @Override
        public SecurityIdentity identity() {
            return identity;
        }

        @Override
        public Optional<UserId> user() {
            return QuarkPressPrincipal.of(identity).map(QuarkPressPrincipal::user);
        }

        @Override
        public int authCode() {
            return user().isPresent() ? 403 : 401;
        }
    }
}
