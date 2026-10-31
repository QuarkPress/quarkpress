package org.quarkpress.testing.wprestparity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.quarkpress.testing.wporacle.OracleJson;
import org.quarkpress.testing.wporacle.ParityRun;

/**
 * Compares the answers of one scenario: per request the status, the compared headers and the normalised body. The
 * aspects are {@code req<n>:status}, {@code req<n>:header:<lower-case name>} and either {@code req<n>:body} (equal
 * bodies, one comparison) or one {@code req<n>:body:<JSON pointer>} per differing leaf ({@code <absent>} where one
 * side has no value). JSON bodies are compared parsed ({@code []} and {@code {}} distinct, key order ignored); other
 * bodies as text.
 *
 * <p>The index ({@code namespaces} and {@code routes}) and a namespace's index ({@code namespace} and
 * {@code routes}) are compared for everything except a fixed, named set of namespaces, routes and index links that
 * QuarkPress does not serve yet: each of those WordPress lists is dropped and recorded as its own aspect
 * {@code req<n>:body:wp-only:<pointer>}, a difference the known-differences file must list. Front-end {@code link}
 * values that differ while both sides have one are recorded as {@code req<n>:body:link-form:<pointer>} and then
 * compared as equal, so a {@code link} one side lacks still differs.
 */
public final class RestComparison {

    /** The headers compared on every answer; {@code Vary} only when the request sent an {@code Origin}. */
    public static final List<String> HEADERS = List.of(
            "allow",
            "x-wp-total",
            "x-wp-totalpages",
            "link",
            "content-type",
            "location",
            "x-robots-tag",
            "access-control-expose-headers",
            "access-control-allow-headers");

    static final String ABSENT = "<absent>";

    private final Normalizer normalizer = new Normalizer();
    private final Normalizer.Refs wordpressRefs;
    private final Normalizer.Refs quarkpressRefs;
    private final String sitePath;

    /**
     * {@code *Refs} map the scenario's refs to each side's ids ({@code "t:news" -> 12}); both sides serve the scenario
     * at {@code sitePath}.
     */
    public RestComparison(Map<String, Long> wordpressRefs, Map<String, Long> quarkpressRefs, String sitePath) {
        this.wordpressRefs = Normalizer.Refs.of(wordpressRefs);
        this.quarkpressRefs = Normalizer.Refs.of(quarkpressRefs);
        this.sitePath = Objects.requireNonNull(sitePath, "sitePath");
    }

    public void compare(RestScenario scenario, List<Exchange> wordpress, List<Exchange> quarkpress, ParityRun run) {
        if (wordpress.size() != quarkpress.size()) {
            throw new IllegalArgumentException(scenario.id() + ": " + wordpress.size() + " WordPress answers, "
                    + quarkpress.size() + " QuarkPress answers");
        }
        for (int i = 0; i < wordpress.size(); i++) {
            String prefix = "req" + (i + 1) + ":";
            Exchange wp = wordpress.get(i);
            Exchange qp = quarkpress.get(i);
            run.compare(scenario.id(), prefix + "status", wp.status(), qp.status());
            for (String name : HEADERS) {
                compareHeader(scenario.id(), prefix, name, wp, qp, run);
            }
            if (wp.call().headers().keySet().stream().anyMatch(h -> h.equalsIgnoreCase("Origin"))) {
                compareHeader(scenario.id(), prefix, "vary", wp, qp, run);
            }
            compareBody(scenario.id(), prefix, wp, qp, run);
        }
    }

    private void compareHeader(String caseId, String prefix, String name, Exchange wp, Exchange qp, ParityRun run) {
        run.compare(
                caseId,
                prefix + "header:" + name,
                wp.header(name)
                        .map(v -> normalizer.header(name, v, wordpressRefs, sitePath))
                        .orElse(ABSENT),
                qp.header(name)
                        .map(v -> normalizer.header(name, v, quarkpressRefs, sitePath))
                        .orElse(ABSENT));
    }

    private void compareBody(String caseId, String prefix, Exchange wp, Exchange qp, ParityRun run) {
        Normalizer.Kind kind = Normalizer.Kind.ofTarget(wp.call().target());
        Optional<JsonNode> wpJson = json(wp.body());
        Optional<JsonNode> qpJson = json(qp.body());
        if (wpJson.isEmpty() || qpJson.isEmpty()) {
            String wpText = wpJson.map(n ->
                            normalizer.body(n, wordpressRefs, sitePath, kind).toString())
                    .orElseGet(() -> normalizer.text(wp.text(), wordpressRefs, sitePath));
            String qpText = qpJson.map(n ->
                            normalizer.body(n, quarkpressRefs, sitePath, kind).toString())
                    .orElseGet(() -> normalizer.text(qp.text(), quarkpressRefs, sitePath));
            run.compare(caseId, prefix + "body", wpText, qpText);
            return;
        }
        JsonNode wpBody = normalizer.body(wpJson.get(), wordpressRefs, sitePath, kind);
        JsonNode qpBody = normalizer.body(qpJson.get(), quarkpressRefs, sitePath, kind);
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
        List<String[]> links = new ArrayList<>();
        linkForms("", wpBody, qpBody, links);
        for (String[] link : links) {
            run.compare(caseId, prefix + "body:link-form:" + link[0], link[1], link[2]);
        }
        if (wpBody.equals(qpBody)) {
            run.compare(caseId, prefix + "body", "same", "same");
            return;
        }
        List<String[]> leaves = new ArrayList<>();
        diff("", wpBody, qpBody, leaves);
        for (String[] leaf : leaves) {
            run.compare(caseId, prefix + "body:" + leaf[0], leaf[1], leaf[2]);
        }
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
        return json(exchange.body()).orElse(com.fasterxml.jackson.databind.node.MissingNode.getInstance());
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

    /** The namespaces WordPress serves and QuarkPress does not serve yet. */
    public static final Set<String> NOT_SERVED_NAMESPACES =
            Set.of("oembed/1.0", "wp-site-health/v1", "wp-block-editor/v1", "wp-abilities/v1");

    /**
     * The routes WordPress serves and QuarkPress does not serve yet: each entry with every route below it. Only these
     * are dropped from an index, so a route QuarkPress serves but stops listing still differs.
     */
    public static final List<String> NOT_SERVED_ROUTES = List.of(
            "/batch/v1",
            "/oembed/1.0",
            "/wp-site-health/v1",
            "/wp-block-editor/v1",
            "/wp-abilities/v1",
            "/wp/v2/posts",
            "/wp/v2/pages",
            "/wp/v2/media",
            "/wp/v2/menu-items",
            "/wp/v2/blocks",
            "/wp/v2/templates",
            "/wp/v2/template-parts",
            "/wp/v2/global-styles",
            "/wp/v2/navigation",
            "/wp/v2/font-families",
            "/wp/v2/font-collections",
            "/wp/v2/menus",
            "/wp/v2/menu-locations",
            "/wp/v2/wp_pattern_category",
            "/wp/v2/users/(?P<user_id>(?:[\\d]+|me))/application-passwords",
            "/wp/v2/comments",
            "/wp/v2/search",
            "/wp/v2/block-renderer",
            "/wp/v2/block-types",
            "/wp/v2/block-directory",
            "/wp/v2/block-patterns",
            "/wp/v2/pattern-directory",
            "/wp/v2/themes",
            "/wp/v2/plugins",
            "/wp/v2/sidebars",
            "/wp/v2/widget-types",
            "/wp/v2/widgets",
            "/wp/v2/icons",
            "/wp/v2/icon-collections",
            "/wp/v2/view-config");

    /** The index links WordPress gives an editor and QuarkPress does not have yet (the active theme's). */
    public static final List<String> NOT_SERVED_INDEX_LINKS = List.of("wp:active-theme", "curies");

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
     * links that QuarkPress does not serve yet ({@link #NOT_SERVED_NAMESPACES}, {@link #NOT_SERVED_ROUTES},
     * {@link #NOT_SERVED_INDEX_LINKS}) and that QuarkPress's body lacks; answers the JSON pointer of each removed one.
     * Anything else either side lists is compared.
     */
    static List<String> dropWordPressOnly(JsonNode wordpress, JsonNode quarkpress, boolean indexRequest) {
        List<String> dropped = new ArrayList<>();
        if (!wordpress.isObject() || !quarkpress.isObject()) {
            return dropped;
        }
        boolean index = indexRequest || (wordpress.has("namespaces") && quarkpress.has("namespaces"));
        boolean namespace = wordpress.has("namespace") && quarkpress.has("namespace");
        if (!index && !namespace) {
            return dropped;
        }
        if (wordpress.path("routes").isObject() && quarkpress.path("routes").isObject()) {
            ObjectNode wpRoutes = (ObjectNode) wordpress.get("routes");
            List<String> extra = new ArrayList<>();
            for (Iterator<String> it = wpRoutes.fieldNames(); it.hasNext(); ) {
                String route = it.next();
                if (notServedRoute(route) && !quarkpress.get("routes").has(route)) {
                    extra.add(route);
                }
            }
            for (String route : extra) {
                wpRoutes.remove(route);
                dropped.add("/routes/" + escape(route));
            }
        }
        if (wordpress.path("namespaces").isArray()
                && quarkpress.path("namespaces").isArray()) {
            Set<String> served = new LinkedHashSet<>();
            quarkpress.get("namespaces").forEach(n -> served.add(n.asText()));
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
        if (index && wordpress.path("_links").isObject()) {
            ObjectNode wpLinks = (ObjectNode) wordpress.get("_links");
            for (String rel : NOT_SERVED_INDEX_LINKS) {
                if (wpLinks.has(rel) && !quarkpress.path("_links").has(rel)) {
                    wpLinks.remove(rel);
                    dropped.add("/_links/" + escape(rel));
                }
            }
        }
        return dropped;
    }

    /**
     * Where both sides have a front-end {@code link} (a text under the key {@code link}) and the two differ, records the
     * pair as {@code <pointer>} and puts {@code {link}} in both bodies, so the rest of the comparison still sees whether
     * each side has the field.
     */
    static void linkForms(String pointer, JsonNode wordpress, JsonNode quarkpress, List<String[]> out) {
        if (wordpress.isObject() && quarkpress.isObject()) {
            for (Iterator<String> it = wordpress.fieldNames(); it.hasNext(); ) {
                String key = it.next();
                JsonNode a = wordpress.get(key);
                JsonNode b = quarkpress.get(key);
                if (b == null) {
                    continue;
                }
                String child = pointer + "/" + escape(key);
                if (key.equals("link") && a.isTextual() && b.isTextual()) {
                    if (!a.equals(b)) {
                        out.add(new String[] {child, a.toString(), b.toString()});
                        ((ObjectNode) wordpress).put(key, "{link}");
                        ((ObjectNode) quarkpress).put(key, "{link}");
                    }
                } else {
                    linkForms(child, a, b, out);
                }
            }
        } else if (wordpress.isArray() && quarkpress.isArray()) {
            for (int i = 0; i < Math.min(wordpress.size(), quarkpress.size()); i++) {
                linkForms(pointer + "/" + i, wordpress.get(i), quarkpress.get(i), out);
            }
        }
    }

    /** Appends one {pointer, wordpress, quarkpress} entry per differing leaf. */
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
