package org.quarkword.testing.wprestparity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.quarkword.testing.wporacle.OracleJson;
import org.quarkword.testing.wporacle.ParityRun;

/**
 * Compares the answers of one scenario: per request the status, the compared headers and the normalised body. The
 * aspects are {@code req<n>:status}, {@code req<n>:header:<lower-case name>} and either {@code req<n>:body} (equal
 * bodies, one comparison) or one {@code req<n>:body:<JSON pointer>} per differing leaf ({@code <absent>} where one
 * side has no value). JSON bodies are compared parsed ({@code []} and {@code {}} distinct, key order ignored); other
 * bodies as text. For the scenarios of {@link #RAW_SCENARIOS}, whose point is the bytes (PHP's escapes, the order of
 * keys, pretty printing), a JSON body that compares equal parsed is also compared as text, as {@code req<n>:body:raw}:
 * the bytes as sent, with each value the normalisation changed written as its normalised JSON.
 *
 * <p>The index ({@code namespaces} and {@code routes}) and a namespace's index ({@code namespace} and
 * {@code routes}) are compared for everything except a fixed, named set of namespaces, routes and index links that
 * QuarkWord does not serve yet: each of those WordPress lists is dropped and recorded as its own aspect
 * {@code req<n>:body:wp-only:<pointer>}, a difference the known-differences file must list; likewise a namespace only
 * QuarkWord serves ({@link #QUARKWORD_ONLY_NAMESPACES}) is dropped from QuarkWord's list as
 * {@code req<n>:body:qw-only:<pointer>}. Front-end links ({@code link}, {@code permalink_template},
 * {@code preview_link}, a search item's {@code url}, the {@code rel="alternate"} entry of a {@code Link} header) are
 * compared as they are, as every other value.
 */
public final class RestComparison {

    /** The headers compared on every answer. */
    public static final List<String> HEADERS = List.of(
            "allow",
            "x-wp-total",
            "x-wp-totalpages",
            "link",
            "content-type",
            "location",
            "x-robots-tag",
            "x-content-type-options",
            "cache-control",
            "expires",
            "vary",
            "access-control-expose-headers",
            "access-control-allow-headers",
            "x-wp-upload-attachment-id");

    /** The headers compared on the answers to requests that sent an {@code Origin}. */
    public static final List<String> ORIGIN_HEADERS =
            List.of("access-control-allow-origin", "access-control-allow-credentials", "access-control-allow-methods");

    /** The scenarios whose bodies are also compared as bytes: PHP's JSON escapes, and the order of keys. */
    public static final Set<String> RAW_SCENARIOS = Set.of("d14", "d21", "f03", "f04", "f05");

    static final String ABSENT = "<absent>";

    private final Normalizer normalizer = new Normalizer();
    private final Normalizer.Refs wordpressRefs;
    private final Normalizer.Refs quarkwordRefs;
    private final String sitePath;

    /**
     * {@code *Refs} map the scenario's refs to each side's ids ({@code "t:news" -> 12}); both sides serve the scenario
     * at {@code sitePath}.
     */
    public RestComparison(Map<String, Long> wordpressRefs, Map<String, Long> quarkwordRefs, String sitePath) {
        this.wordpressRefs = Normalizer.Refs.of(wordpressRefs);
        this.quarkwordRefs = Normalizer.Refs.of(quarkwordRefs);
        this.sitePath = Objects.requireNonNull(sitePath, "sitePath");
    }

    public void compare(RestScenario scenario, List<Exchange> wordpress, List<Exchange> quarkword, ParityRun run) {
        if (wordpress.size() != quarkword.size()) {
            throw new IllegalArgumentException(scenario.id() + ": " + wordpress.size() + " WordPress answers, "
                    + quarkword.size() + " QuarkWord answers");
        }
        for (int i = 0; i < wordpress.size(); i++) {
            String prefix = "req" + (i + 1) + ":";
            Exchange wp = wordpress.get(i);
            Exchange qp = quarkword.get(i);
            run.compare(scenario.id(), prefix + "status", wp.status(), qp.status());
            for (String name : HEADERS) {
                compareHeader(scenario.id(), prefix, name, wp, qp, run);
            }
            if (wp.call().headers().keySet().stream().anyMatch(h -> h.equalsIgnoreCase("Origin"))) {
                for (String name : ORIGIN_HEADERS) {
                    compareHeader(scenario.id(), prefix, name, wp, qp, run);
                }
            }
            compareBody(scenario.id(), prefix, wp, qp, run, RAW_SCENARIOS.contains(scenario.id()));
        }
    }

    private void compareHeader(String caseId, String prefix, String name, Exchange wp, Exchange qp, ParityRun run) {
        String wpValue = wp.header(name)
                .map(v -> normalizer.header(name, v, wordpressRefs, sitePath))
                .orElse(ABSENT);
        run.compare(
                caseId,
                prefix + "header:" + name,
                wpValue,
                qp.header(name)
                        .map(v -> normalizer.header(name, v, quarkwordRefs, sitePath))
                        .orElse(ABSENT));
    }

    private void compareBody(String caseId, String prefix, Exchange wp, Exchange qp, ParityRun run, boolean raw) {
        Normalizer.Kind kind = Normalizer.Kind.ofTarget(wp.call().target());
        Optional<JsonNode> wpJson = json(wp.body());
        Optional<JsonNode> qpJson = json(qp.body());
        if (wpJson.isEmpty() || qpJson.isEmpty()) {
            String wpText = wpJson.map(n -> normalizer
                            .body(n, wordpressRefs, sitePath, kind, Optional.of(wp.sentAt()))
                            .toString())
                    .orElseGet(() -> normalizer.text(wp.text(), wordpressRefs, sitePath));
            String qpText = qpJson.map(n -> normalizer
                            .body(n, quarkwordRefs, sitePath, kind, Optional.of(qp.sentAt()))
                            .toString())
                    .orElseGet(() -> normalizer.text(qp.text(), quarkwordRefs, sitePath));
            run.compare(caseId, prefix + "body", wpText, qpText);
            return;
        }
        JsonNode wpBody;
        JsonNode qpBody;
        if (EditorPreloadPaths.isEditorTarget(wp.call().target())) {
            wpBody = preloadMap(wpJson.get(), wordpressRefs, wp.sentAt());
            qpBody = preloadMap(qpJson.get(), quarkwordRefs, qp.sentAt());
        } else {
            wpBody = normalizer.body(wpJson.get(), wordpressRefs, sitePath, kind, Optional.of(wp.sentAt()));
            qpBody = normalizer.body(qpJson.get(), quarkwordRefs, sitePath, kind, Optional.of(qp.sentAt()));
        }
        boolean indexRequest = isIndexTarget(wp.call().target());
        List<String> dropped = dropWordPressOnly(wpBody, qpBody, indexRequest);
        if (isEnvelope(wpBody) && isEnvelope(qpBody)) {
            for (String pointer : dropWordPressOnly(wpBody.get("body"), qpBody.get("body"), indexRequest)) {
                dropped.add("/body" + pointer);
            }
        }
        for (String pointer : dropped) {
            run.compare(caseId, prefix + "body:wp-only:" + pointer, "listed", ABSENT);
        }
        List<String> quarkwordOnly = dropQuarkWordOnly(wpBody, qpBody);
        if (isEnvelope(wpBody) && isEnvelope(qpBody)) {
            for (String pointer : dropQuarkWordOnly(wpBody.get("body"), qpBody.get("body"))) {
                quarkwordOnly.add("/body" + pointer);
            }
        }
        for (String pointer : quarkwordOnly) {
            run.compare(caseId, prefix + "body:qw-only:" + pointer, ABSENT, "listed");
        }
        if (wpBody.equals(qpBody)) {
            run.compare(caseId, prefix + "body", "same", "same");
            if (raw) {
                run.compare(
                        caseId,
                        prefix + "body:raw",
                        RawJson.normalised(wp.body(), wpJson.get(), wpBody),
                        RawJson.normalised(qp.body(), qpJson.get(), qpBody));
            }
            return;
        }
        List<String[]> leaves = new ArrayList<>();
        diff("", wpBody, qpBody, leaves);
        for (String[] leaf : leaves) {
            run.compare(caseId, prefix + "body:" + leaf[0], leaf[1], leaf[2]);
        }
    }

    /**
     * A preload map (the answer of a preload comparison, {@link EditorPreloadPaths}) normalised entry by entry: each
     * key, a preloaded path, with its ids as refs (each side's map names its own post, autosaves and author ids), and
     * each entry's value normalised as the answer of that path would be (an {@code id} of a users route is a user's,
     * whatever the fields kept). The entries under {@code OPTIONS} go the same way; anything else is normalised as a
     * body.
     */
    private JsonNode preloadMap(JsonNode map, Normalizer.Refs refs, Instant sentAt) {
        if (!map.isObject()) {
            return normalizer.body(map, refs, sitePath, Normalizer.Kind.ANY, Optional.of(sentAt));
        }
        ObjectNode renamed = OracleJson.MAPPER.createObjectNode();
        for (Iterator<Map.Entry<String, JsonNode>> it = map.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            if (entry.getKey().equals(EditorPreloadPaths.OPTIONS)) {
                renamed.set(entry.getKey(), preloadMap(entry.getValue(), refs, sentAt));
            } else {
                Normalizer.Kind kind = Normalizer.Kind.ofTarget(entry.getKey());
                renamed.set(
                        normalizer.route(entry.getKey(), refs),
                        normalizer.body(entry.getValue(), refs, sitePath, kind, Optional.of(sentAt)));
            }
        }
        return renamed;
    }

    /**
     * Whether a request's target is the API index: its path ends in {@code /wp-json} or {@code /wp-json/}, or its
     * {@code rest_route} is {@code /}. (With {@code _fields} the index body need not show {@code namespaces}.)
     */
    static boolean isIndexTarget(String target) {
        int question = target.indexOf('?');
        String path = question < 0 ? target : target.substring(0, question);
        if (path.endsWith("/wp-json/") || path.endsWith("/wp-json")) {
            return true;
        }
        String query = question < 0 ? "" : "&" + target.substring(question + 1) + "&";
        return query.contains("&rest_route=/&")
                || query.contains("&rest_route=%2F&")
                || query.contains("&rest_route=%2f&");
    }

    /** Whether a body is an {@code _envelope}: {@code body}, {@code status} and {@code headers}. */
    private static boolean isEnvelope(JsonNode body) {
        return body.isObject() && body.size() == 3 && body.has("body") && body.has("status") && body.has("headers");
    }

    /** The exchange's body parsed as JSON; a missing node when it is not a JSON object or array. */
    public static JsonNode jsonBody(Exchange exchange) {
        return json(exchange.body()).orElse(MissingNode.getInstance());
    }

    /** The body parsed as JSON when it is a JSON object or array. */
    static Optional<JsonNode> json(byte[] body) {
        int i = 0;
        while (i < body.length && (body[i] == ' ' || body[i] == '\n' || body[i] == '\r' || body[i] == '\t')) {
            i++;
        }
        if (i == body.length || (body[i] != '{' && body[i] != '[')) {
            return Optional.empty();
        }
        try {
            JsonNode node = OracleJson.MAPPER.readTree(body);
            return node == null || node.isMissingNode() ? Optional.empty() : Optional.of(node);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** The namespaces WordPress serves and QuarkWord does not serve yet. */
    public static final Set<String> NOT_SERVED_NAMESPACES =
            Set.of("wp-site-health/v1", "wp-block-editor/v1", "wp-abilities/v1");

    /** The namespaces only QuarkWord serves: the editor's lock, preview and draft routes. */
    public static final Set<String> QUARKWORD_ONLY_NAMESPACES = Set.of("quarkword/v1");

    /**
     * The routes WordPress serves and QuarkWord does not serve yet: each entry with every route below it. Only these
     * are dropped from an index, so a route QuarkWord serves but stops listing still differs.
     */
    public static final List<String> NOT_SERVED_ROUTES = List.of(
            "/batch/v1",
            "/oembed/1.0/embed",
            "/wp-site-health/v1",
            "/wp-block-editor/v1",
            "/wp-abilities/v1",
            "/wp/v2/posts",
            "/wp/v2/pages",
            "/wp/v2/books",
            "/wp/v2/media",
            "/wp/v2/menu-items",
            "/wp/v2/templates",
            "/wp/v2/template-parts",
            "/wp/v2/global-styles",
            "/wp/v2/navigation",
            "/wp/v2/font-families",
            "/wp/v2/font-collections",
            "/wp/v2/menus",
            "/wp/v2/menu-locations",
            "/wp/v2/users/(?P<user_id>(?:[\\d]+|me))/application-passwords",
            "/wp/v2/comments",
            "/wp/v2/search",
            "/wp/v2/block-directory",
            "/wp/v2/pattern-directory",
            "/wp/v2/plugins",
            "/wp/v2/sidebars",
            "/wp/v2/widget-types",
            "/wp/v2/widgets",
            "/wp/v2/icons",
            "/wp/v2/icon-collections",
            "/wp/v2/view-config");

    static boolean notServedRoute(String route) {
        for (String served : NOT_SERVED_ROUTES) {
            if (route.equals(served) || (route.startsWith(served) && route.charAt(served.length()) == '/')) {
                return true;
            }
        }
        return false;
    }

    /**
     * For an index or a namespace's index on both sides: removes from WordPress's body the namespaces, routes and index
     * links that QuarkWord does not serve yet ({@link #NOT_SERVED_NAMESPACES}, {@link #NOT_SERVED_ROUTES}) and that
     * QuarkWord's body lacks; answers the JSON pointer of each removed one. Anything else either side lists is
     * compared, the index links among it since the active theme is linked.
     */
    static List<String> dropWordPressOnly(JsonNode wordpress, JsonNode quarkword, boolean indexRequest) {
        List<String> dropped = new ArrayList<>();
        if (!wordpress.isObject() || !quarkword.isObject()) {
            return dropped;
        }
        boolean index = indexRequest || (wordpress.has("namespaces") && quarkword.has("namespaces"));
        boolean namespace = wordpress.has("namespace") && quarkword.has("namespace");
        if (!index && !namespace) {
            return dropped;
        }
        if (wordpress.path("routes").isObject() && quarkword.path("routes").isObject()) {
            ObjectNode wpRoutes = (ObjectNode) wordpress.get("routes");
            List<String> extra = new ArrayList<>();
            for (Iterator<String> it = wpRoutes.fieldNames(); it.hasNext(); ) {
                String route = it.next();
                if (notServedRoute(route) && !quarkword.get("routes").has(route)) {
                    extra.add(route);
                }
            }
            for (String route : extra) {
                wpRoutes.remove(route);
                dropped.add("/routes/" + escape(route));
            }
        }
        if (wordpress.path("namespaces").isArray()
                && quarkword.path("namespaces").isArray()) {
            Set<String> served = new LinkedHashSet<>();
            quarkword.get("namespaces").forEach(n -> served.add(n.asText()));
            ArrayNode namespaces = (ArrayNode) wordpress.get("namespaces");
            ArrayNode kept = namespaces.arrayNode();
            for (JsonNode name : namespaces) {
                if (NOT_SERVED_NAMESPACES.contains(name.asText()) && !served.contains(name.asText())) {
                    dropped.add("/namespaces/" + escape(name.asText()));
                } else {
                    kept.add(name);
                }
            }
            namespaces.removeAll();
            namespaces.addAll(kept);
        }
        return dropped;
    }

    /**
     * For an index on both sides: removes from QuarkWord's {@code namespaces} the ones only QuarkWord serves
     * ({@link #QUARKWORD_ONLY_NAMESPACES}) that WordPress's lacks; answers the JSON pointer of each removed one. The
     * rest of the list is compared.
     */
    static List<String> dropQuarkWordOnly(JsonNode wordpress, JsonNode quarkword) {
        List<String> dropped = new ArrayList<>();
        if (!wordpress.path("namespaces").isArray()
                || !quarkword.path("namespaces").isArray()) {
            return dropped;
        }
        Set<String> listed = new LinkedHashSet<>();
        wordpress.get("namespaces").forEach(n -> listed.add(n.asText()));
        ArrayNode namespaces = (ArrayNode) quarkword.get("namespaces");
        ArrayNode kept = namespaces.arrayNode();
        for (JsonNode name : namespaces) {
            if (QUARKWORD_ONLY_NAMESPACES.contains(name.asText()) && !listed.contains(name.asText())) {
                dropped.add("/namespaces/" + escape(name.asText()));
            } else {
                kept.add(name);
            }
        }
        namespaces.removeAll();
        namespaces.addAll(kept);
        return dropped;
    }

    /** Appends one {pointer, wordpress, quarkword} entry per differing leaf. */
    static void diff(String pointer, JsonNode wp, JsonNode qp, List<String[]> out) {
        if (wp.equals(qp)) {
            return;
        }
        if (wp.isObject() && qp.isObject()) {
            Set<String> keys = new LinkedHashSet<>();
            wp.fieldNames().forEachRemaining(keys::add);
            qp.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                String child = pointer + "/" + escape(key);
                JsonNode a = wp.get(key);
                JsonNode b = qp.get(key);
                if (a == null || b == null) {
                    out.add(new String[] {child, render(a), render(b)});
                } else {
                    diff(child, a, b, out);
                }
            }
            return;
        }
        if (wp.isArray() && qp.isArray()) {
            int size = Math.max(wp.size(), qp.size());
            for (int i = 0; i < size; i++) {
                String child = pointer + "/" + i;
                if (i >= wp.size() || i >= qp.size()) {
                    out.add(new String[] {child, render(wp.get(i)), render(qp.get(i))});
                } else {
                    diff(child, wp.get(i), qp.get(i), out);
                }
            }
            return;
        }
        out.add(new String[] {pointer, render(wp), render(qp)});
    }

    private static String render(JsonNode node) {
        return node == null ? ABSENT : node.toString();
    }

    /** A JSON pointer token: {@code ~} as {@code ~0}, {@code /} as {@code ~1}. */
    static String escape(String token) {
        StringBuilder out = new StringBuilder(token.length());
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '~') {
                out.append("~0");
            } else if (c == '/') {
                out.append("~1");
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
