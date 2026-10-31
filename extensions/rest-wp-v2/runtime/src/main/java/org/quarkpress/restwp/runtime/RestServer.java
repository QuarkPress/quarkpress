package org.quarkpress.restwp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
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
import java.util.function.Supplier;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteNotResolvedException;
import org.quarkpress.core.UserId;
import org.quarkpress.restwp.Arg;
import org.quarkpress.restwp.Endpoint;
import org.quarkpress.restwp.Links;
import org.quarkpress.restwp.PermissionResult;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestResponse;
import org.quarkpress.restwp.RestRouteProvider;
import org.quarkpress.restwp.RestUrls;
import org.quarkpress.security.QuarkPressPrincipal;

/**
 * Serves REST requests in WordPress's order: the method override, the site gate, OPTIONS (answered with the route's
 * data before any match), the route match, the JSON body check, the arguments' validation and sanitizing, the
 * endpoint's permission, then its handler; an exception that escapes the permission or the handler becomes the error
 * WordPress would answer. Every answer of a matched route, an error included, gets the {@code Allow} header for its
 * caller. Every answer then goes through WordPress's response stages (fixed headers, envelope, no-cache, cross-origin
 * headers, pretty printing, HEAD) and is encoded as PHP encodes it.
 */
@ApplicationScoped
public class RestServer {

    static final RestError NO_ROUTE =
            RestError.of("rest_no_route", "No route was found matching the URL and request method.", 404);

    /** WordPress's refusal of a JSONP request when JSONP is disabled, which QuarkPress always is. */
    private static final byte[] JSONP_DISABLED =
            "{\"code\":\"rest_callback_disabled\",\"message\":\"JSONP support is disabled on this site.\"}"
                    .getBytes(StandardCharsets.UTF_8);

    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    @Inject
    SiteGate gate;

    @Inject
    Instance<RestRouteProvider> providers;

    @Inject
    SecurityIdentity identity;

    @Inject
    SiteContext site;

    @Inject
    RestUrls urls;

    @Inject
    RestWpConfig config;

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

    /**
     * Answers a REST request. Whatever fails on the way, an exception or a stack overflow, ends as WordPress's error
     * object, never as the server's own error page.
     */
    public ServedResponse serve(IncomingRequest incoming) {
        return respond(incoming, () -> dispatch(incoming), true);
    }

    /**
     * Answers {@code error}, decided before any route was matched (an authentication failure), through the same stages
     * as {@link #serve}: the site gate first, then the JSONP refusal, then the error in place of the route's answer,
     * with the fixed, no-cache and cross-origin headers, the envelope, pretty printing and HEAD. The caller counts as
     * not logged in.
     */
    public ServedResponse serveAuthFailure(IncomingRequest incoming, RestError error) {
        return respond(incoming, () -> error(error), false);
    }

    /**
     * The stages every REST answer goes through: the site gate (whose refusal of a site that may not be served is sent
     * as it is, and whose redirect gets the fixed headers only); then a {@code _jsonp} request's refusal; then the
     * answer, with the fixed headers, its own headers or the envelope, no-cache, cross-origin headers, pretty printing,
     * and no body for HEAD.
     *
     * @param identified whether the request's identity may be read (false once its authentication failed)
     */
    private ServedResponse respond(IncomingRequest incoming, Supplier<RestResponse> answer, boolean identified) {
        String root = null;
        boolean started = false;
        boolean loggedIn = false;
        try {
            Optional<SiteGate.Gate> gated = gate.decide(incoming);
            if (gated.isPresent() && gated.get() instanceof SiteGate.Gate.Died died) {
                return withoutBodyForHead(incoming, died.response());
            }
            root = apiRoot();
            started = true;
            if (gated.isPresent() && gated.get() instanceof SiteGate.Gate.Redirect redirect) {
                Map<String, List<String>> headers = ResponseHeaders.fixed(root);
                // an empty answer has no content type
                headers.remove("Content-Type");
                replace(headers, redirect.response().headers());
                return sendable(incoming, new ServedResponse(redirect.response().status(), headers, new byte[0]));
            }
            if (asksForJsonp(incoming)) {
                return sendable(incoming, new ServedResponse(400, ResponseHeaders.jsonpRefusal(root), JSONP_DISABLED));
            }
            RestResponse response;
            try {
                response = gated.isPresent() ? error(NO_ROUTE) : answer.get();
            } catch (RuntimeException | StackOverflowError failure) {
                response = error(ErrorMapping.toError(failure, incoming.method(), incoming.target()));
            }
            loggedIn = identified && !identity.isAnonymous();
            return sendable(incoming, finish(incoming, root, response, loggedIn));
        } catch (RuntimeException | StackOverflowError failure) {
            if (!started) {
                return unexpected(failure, incoming);
            }
            // the fixed headers are out once the REST server has started, as in WordPress
            try {
                RestResponse error = error(ErrorMapping.toError(failure, incoming.method(), incoming.target()));
                Map<String, List<String>> headers = ResponseHeaders.fixed(root);
                withCacheAndOrigin(incoming, headers, error.status(), loggedIn);
                return sendable(
                        incoming, new ServedResponse(error.status(), headers, RestJson.encode(error.body(), false)));
            } catch (RuntimeException | StackOverflowError again) {
                return unexpected(failure, incoming);
            }
        }
    }

    /**
     * WordPress's JSONP test: {@code _jsonp} in the query string with a value PHP counts as true (not {@code ""}, not
     * {@code "0"}, not an empty array). JSONP is never enabled, so such a request is always refused.
     */
    static boolean asksForJsonp(IncomingRequest incoming) {
        JsonNode callback = incoming.query().get("_jsonp");
        if (callback == null) {
            return false;
        }
        if (callback.isTextual()) {
            return !RestRequestFilter.phpEmpty(callback);
        }
        return !callback.isContainerNode() || callback.size() > 0;
    }

    /**
     * The response stages after the answer, in WordPress's order: the fixed headers, then the envelope or the answer's
     * own headers (which replace fixed ones of the same name, as a pagination {@code Link} replaces the API root's),
     * the status, the no-cache headers, the cross-origin headers, and the body, pretty printed on request (none for a
     * null body or a 204).
     */
    private ServedResponse finish(IncomingRequest incoming, String root, RestResponse response, boolean loggedIn) {
        Map<String, List<String>> headers = ResponseHeaders.fixed(root);
        RestResponse sent = incoming.query().has("_envelope") ? Envelope.wrap(response) : response;
        replace(headers, sent.headers());
        int status = sent.status();
        withCacheAndOrigin(incoming, headers, status, loggedIn);
        // a null body (an OPTIONS on a route with no shown endpoint) or a 204 is sent without a body, as WordPress does
        byte[] body = status == 204 || sent.body().isNull()
                ? new byte[0]
                : RestJson.encode(sent.body(), incoming.query().has("_pretty"));
        return new ServedResponse(status, headers, body);
    }

    /**
     * The no-cache headers (for a logged-in caller, or a 4xx whose method came from the override header), which also
     * remove {@code Last-Modified}; then the cross-origin headers.
     */
    private void withCacheAndOrigin(
            IncomingRequest incoming, Map<String, List<String>> headers, int status, boolean loggedIn) {
        if (loggedIn || (MethodOverride.of(incoming).fromHeader() && status >= 400 && status < 500)) {
            replace(headers, ResponseHeaders.noCache());
            removeIgnoringCase(headers, "Last-Modified");
        }
        boolean anonymousGet = !loggedIn && upper(incoming.method()).equals("GET");
        replace(
                headers,
                Cors.headers(
                        Optional.ofNullable(incoming.joinedHeader("Origin")),
                        anonymousGet,
                        config.cors().origins().orElse(List.of())));
    }

    /** Sets {@code more} over {@code headers}: a header of the same name, in any ASCII case, is replaced. */
    static void replace(Map<String, List<String>> headers, Map<String, List<String>> more) {
        more.forEach((name, values) -> {
            removeIgnoringCase(headers, name);
            headers.put(name, values);
        });
    }

    private static void removeIgnoringCase(Map<String, List<String>> headers, String name) {
        headers.keySet().removeIf(key -> Cors.equalsIgnoringAsciiCase(key, name));
    }

    /** The site's API root, or null when no site is resolved (the request's own host is never named). */
    private String apiRoot() {
        if (!site.isResolved()) {
            return null;
        }
        try {
            return urls.root();
        } catch (SiteNotResolvedException unresolved) {
            return null;
        }
    }

    /**
     * The request's method after WordPress's override, and whether it came from the {@code X-HTTP-Method-Override}
     * header: a textual {@code _method} in the query wins, then the header, then the real method; upper-cased.
     */
    record MethodOverride(String method, boolean fromHeader) {

        static MethodOverride of(IncomingRequest incoming) {
            JsonNode query = incoming.query().get("_method");
            if (query != null && query.isTextual()) {
                return new MethodOverride(upper(query.textValue()), false);
            }
            String header = incoming.header("X-HTTP-Method-Override");
            if (header != null) {
                return new MethodOverride(upper(header), true);
            }
            return new MethodOverride(upper(incoming.method()), false);
        }
    }

    /** The answer as it goes out: header values with whitespace runs collapsed, and no body for HEAD. */
    private static ServedResponse sendable(IncomingRequest incoming, ServedResponse response) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        response.headers().forEach((name, values) -> {
            List<String> collapsed = new ArrayList<>(values.size());
            values.forEach(value -> collapsed.add(ResponseHeaders.collapse(value)));
            headers.put(name, List.copyOf(collapsed));
        });
        return withoutBodyForHead(incoming, new ServedResponse(response.status(), headers, response.body()));
    }

    /** HEAD, sent or asked for through the override, gets the headers and the status and no body. */
    private static ServedResponse withoutBodyForHead(IncomingRequest incoming, ServedResponse response) {
        boolean head =
                upper(incoming.method()).equals("HEAD") || method(incoming).equals("HEAD");
        return head ? new ServedResponse(response.status(), response.headers(), new byte[0]) : response;
    }

    private RestResponse dispatch(IncomingRequest incoming) {
        String method = method(incoming);
        if (method.equals("OPTIONS")) {
            return options(incoming);
        }
        Optional<RouteTable.Match> found = table().match(incoming.route(), method);
        if (found.isEmpty()) {
            return error(NO_ROUTE);
        }
        RouteTable.Match match = found.get();
        ParamSources sources = new ParamSources(
                jsonParams(incoming),
                bodyParams(incoming, method),
                incoming.query(),
                match.urlParams(),
                defaults(match));
        Served request = new Served(
                method, match.matchedRoute(), match.endpoint(), incoming.route(), sources, incoming.query(), this);
        return withAllow(match.matchedRoute(), request, answer(incoming, match, sources, request));
    }

    /** The matched request's answer: the JSON check, the arguments, the permission, then the handler. */
    private static RestResponse answer(
            IncomingRequest incoming, RouteTable.Match match, ParamSources sources, Served request) {
        Optional<RestJsonFailure> invalidJson = jsonFailure(incoming);
        if (invalidJson.isPresent()) {
            return error(invalidJson(invalidJson.get()));
        }
        try {
            Optional<RestError> invalid = ArgChecks.check(match.endpoint().args(), sources);
            if (invalid.isPresent()) {
                return error(invalid.get());
            }
            if (match.endpoint().permission().check(request) instanceof PermissionResult.Denied denied) {
                return error(denied.error());
            }
            return match.endpoint().handler().handle(request);
        } catch (RuntimeException failure) {
            return error(ErrorMapping.toError(failure, request));
        }
    }

    /**
     * WordPress's answer to OPTIONS, before any route is matched for a method: the data of the first route whose
     * pattern matches the path, in the help context, with the {@code Allow} header computed against this request
     * (its own parameters and the route's groups, no defaults); 200 {@code []} without {@code Allow} when no route
     * matches; 200 with no body (WordPress's null data) when none of the route's endpoints is shown in the index.
     * Nothing is validated and no handler runs.
     */
    private RestResponse options(IncomingRequest incoming) {
        Optional<RouteTable.MatchedRoute> found = table().firstMatching(incoming.route());
        if (found.isEmpty()) {
            return RestResponse.ok(RestJson.NODES.arrayNode());
        }
        RouteTable.MatchedRoute matched = found.get();
        ParamSources sources = new ParamSources(
                jsonParams(incoming),
                bodyParams(incoming, "OPTIONS"),
                incoming.query(),
                matched.urlParams(),
                RestJson.NODES.objectNode());
        Served request = new Served("OPTIONS", matched, null, incoming.route(), sources, incoming.query(), this);
        ObjectNode data = RouteData.of(matched.route(), "help", urls);
        return withAllow(matched, request, RestResponse.ok(data == null ? NullNode.getInstance() : data));
    }

    /** {@code response} with the {@code Allow} header for this caller, set last; unchanged when nothing is allowed. */
    private static RestResponse withAllow(RouteTable.MatchedRoute matched, Served request, RestResponse response) {
        return AllowHeader.compute(matched, request)
                .map(allow -> response.withHeader("Allow", allow))
                .orElse(response);
    }

    private static ServedResponse unexpected(Throwable failure, IncomingRequest incoming) {
        return withoutBodyForHead(
                incoming, render(error(ErrorMapping.toError(failure, incoming.method(), incoming.target()))));
    }

    /**
     * WordPress's refusal of a request before its REST server starts (a {@code rest_route} that is not a string, or
     * that differs between the query and the form): the site gate's answer when the site may not be served, as
     * WordPress checks the site first; otherwise 400, a JSON error object for a JSON request and an HTML page for any
     * other, with no-cache headers and none of the REST headers (no fixed headers, no cross-origin headers, no
     * envelope, no JSONP refusal, no pretty printing: the REST server never runs); HEAD gets no body.
     */
    public ServedResponse refuse(IncomingRequest incoming, String code, String message) {
        try {
            return withoutBodyForHead(
                    incoming,
                    gate.check(incoming).orElseGet(() -> SiteGate.died(400, code, message, SiteGate.isJson(incoming))));
        } catch (RuntimeException | StackOverflowError failure) {
            return unexpected(failure, incoming);
        }
    }

    /** WordPress's error for a JSON body PHP cannot decode, with PHP's error code and message. */
    static RestError invalidJson(RestJsonFailure failure) {
        ObjectNode data = RestJson.NODES.objectNode();
        data.put("status", 400);
        data.put("json_error_code", failure.code());
        data.put("json_error_message", failure.message());
        return RestError.noStatus("rest_invalid_json", "Invalid JSON body passed.")
                .withData(data);
    }

    /** The method after the override: the query's {@code _method}, else the override header, upper-cased. */
    static String method(IncomingRequest incoming) {
        return MethodOverride.of(incoming).method();
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

    static RestResponse error(RestError error) {
        return new RestResponse(error.status(), error.toJson(), Map.of(), Links.none());
    }

    /** An answer alone, with only its own headers and the JSON content type. */
    static ServedResponse render(RestResponse response) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Content-Type", List.of(ResponseHeaders.JSON_TYPE));
        headers.putAll(response.headers());
        return new ServedResponse(response.status(), headers, RestJson.encode(response.body(), false));
    }

    /** A matched request as handlers see it. */
    private static final class Served implements ServedRequest {

        private final String method;
        private final RouteTable.MatchedRoute matched;
        private final Endpoint endpoint;
        private final String path;
        private final ParamSources sources;
        private final ObjectNode query;
        private final RestServer server;

        Served(
                String method,
                RouteTable.MatchedRoute matched,
                Endpoint endpoint,
                String path,
                ParamSources sources,
                ObjectNode query,
                RestServer server) {
            this.method = method;
            this.matched = matched;
            this.endpoint = endpoint;
            this.path = path;
            this.sources = sources;
            this.query = query;
            this.server = server;
        }

        @Override
        public String method() {
            return method;
        }

        @Override
        public String route() {
            return matched.route().key();
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public Map<String, String> urlParams() {
            return matched.urlParams();
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
            return server.identity;
        }

        @Override
        public Optional<UserId> user() {
            return QuarkPressPrincipal.of(server.identity).map(QuarkPressPrincipal::user);
        }

        @Override
        public int authCode() {
            return user().isPresent() ? 403 : 401;
        }

        @Override
        public boolean declaresArg(String name) {
            if (endpoint == null) {
                return false;
            }
            for (Arg arg : endpoint.args()) {
                if (arg.name().equals(name)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String routeUrl() {
            return server.urls.route(filled(matched.route().pattern().template(), matched.urlParams()));
        }
    }

    /** {@code template} with each {@code {name}} replaced by the group's value. */
    static String filled(String template, Map<String, String> groups) {
        StringBuilder out = new StringBuilder(template.length());
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            int close = c == '{' ? template.indexOf('}', i + 1) : -1;
            if (close > i) {
                String value = groups.get(template.substring(i + 1, close));
                if (value != null) {
                    out.append(value);
                    i = close + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
