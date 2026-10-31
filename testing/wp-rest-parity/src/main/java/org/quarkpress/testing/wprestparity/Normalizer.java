package org.quarkpress.testing.wprestparity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Makes the answers of the two sides comparable. Hosts and ports of local URLs become {@code {host}} (the scheme is
 * kept), the scenario's site path {@code {site}/}; ids of known refs become the ref in id fields and inside local URLs (all-digit path
 * segments and the values of id query parameters); an id field with an unknown id becomes {@code #?<id>}, so it
 * differs between the sides. {@code registered_date} becomes {@code {date:<format>}} (its digits as {@code d}, so a
 * format difference still shows), the index's {@code authentication}
 * {@code {auth}}, and the preferences' {@code _modified} stamp {@code {stamp}}. {@code targetHints} and {@code Allow}
 * are left as they are: they are compared per caller.
 *
 * <p>Users, terms and posts are numbered apart (on WordPress a subsite's terms and posts start again at 1), so an id
 * is looked up among the refs of its kind ({@code u:}, {@code t:}, {@code p:}): {@code author} and {@code reassign}
 * are users; {@code parent}, {@code term_id}, {@code default_category}, {@code cat}, {@code tag},
 * {@code categories} and {@code tags} terms; {@code post}, {@code page_on_front} and {@code page_for_posts} posts; an
 * {@code id} (and {@code include}, {@code exclude} in URLs) takes the kind of its object (a {@code taxonomy} field
 * makes a term, {@code avatar_urls}, {@code username} or {@code roles} a user) or else of the request's route; an id
 * of no known kind is looked up among all refs.
 */
public final class Normalizer {

    /** The kind of an id: a user, a term or a post; {@code ANY} when nothing tells. */
    public enum Kind {
        USER('u'),
        TERM('t'),
        POST('p'),
        ANY('?');

        final char prefix;

        Kind(char prefix) {
            this.prefix = prefix;
        }

        /** The first letter of this kind's refs ({@code u}, {@code t}, {@code p}). */
        public char prefix() {
            return prefix;
        }

        /** The kind of the refs a request's target addresses: by its {@code /wp/v2/<base>} route. */
        public static Kind ofTarget(String target) {
            return routeKind(target);
        }
    }

    /** Ids by kind, each mapped to its ref. */
    public static final class Refs {
        private final Map<Kind, Map<Long, String>> byKind = new HashMap<>();

        private Refs() {
            for (Kind kind : Kind.values()) {
                byKind.put(kind, new HashMap<>());
            }
        }

        /** From ref to id ({@code "t:news" -> 12}): each id under the kind its ref's prefix names. */
        public static Refs of(Map<String, Long> refToId) {
            Refs refs = new Refs();
            refToId.forEach((ref, id) -> refs.put(ref, id));
            return refs;
        }

        /** From id to ref ({@code 12 -> "t:news"}). */
        public static Refs ofIds(Map<Long, String> idToRef) {
            Refs refs = new Refs();
            idToRef.forEach((id, ref) -> refs.put(ref, id));
            return refs;
        }

        private void put(String ref, long id) {
            Kind kind = Kind.ANY;
            for (Kind k : Kind.values()) {
                if (k != Kind.ANY && ref.length() > 1 && ref.charAt(0) == k.prefix && ref.charAt(1) == ':') {
                    kind = k;
                }
            }
            byKind.get(kind).put(id, ref);
        }

        /** The ref of {@code id} among the refs of {@code kind}; for ANY, the refs of every kind that has it. */
        String find(Kind kind, long id) {
            if (kind != Kind.ANY) {
                return byKind.get(kind).get(id);
            }
            TreeSet<String> found = new TreeSet<>();
            for (Map<Long, String> refs : byKind.values()) {
                String ref = refs.get(id);
                if (ref != null) {
                    found.add(ref);
                }
            }
            return found.isEmpty() ? null : String.join("|", found);
        }
    }

    static final Map<String, Kind> ID_FIELDS = Map.of(
            "id", Kind.ANY,
            "parent", Kind.TERM,
            "author", Kind.USER,
            "term_id", Kind.TERM,
            "post", Kind.POST,
            "default_category", Kind.TERM,
            "page_on_front", Kind.POST,
            "page_for_posts", Kind.POST);
    static final Map<String, Kind> ID_QUERY_KEYS = Map.ofEntries(
            Map.entry("cat", Kind.TERM),
            Map.entry("tag", Kind.TERM),
            Map.entry("tag_ID", Kind.TERM),
            Map.entry("categories", Kind.TERM),
            Map.entry("tags", Kind.TERM),
            Map.entry("parent", Kind.TERM),
            Map.entry("author", Kind.USER),
            Map.entry("reassign", Kind.USER),
            Map.entry("p", Kind.POST),
            Map.entry("page_id", Kind.POST),
            Map.entry("post", Kind.POST),
            Map.entry("id", Kind.ANY),
            Map.entry("include", Kind.ANY),
            Map.entry("exclude", Kind.ANY));
    private static final Set<String> USER_HINTS = Set.of("avatar_urls", "username", "roles", "registered_date");
    private static final List<String> LOCAL_HOSTS = List.of("localhost", "127.0.0.1", "[::1]");

    /** The normalised copy of a JSON body; {@code refs} maps ids to refs, each typed by its ref's prefix. */
    public JsonNode body(JsonNode body, Map<Long, String> refs, String sitePath) {
        return body(body, Refs.ofIds(refs), sitePath, Kind.ANY);
    }

    /** The normalised copy of a JSON body; an {@code id} of an object that does not tell its kind is {@code kind}. */
    public JsonNode body(JsonNode body, Refs refs, String sitePath, Kind kind) {
        return walk(body.deepCopy(), refs, sitePath, kind);
    }

    /** The normalised value of a header line (URLs inside it, as in {@code Link} and {@code Location}). */
    public String header(String name, String value, Map<Long, String> refs, String sitePath) {
        return text(value, Refs.ofIds(refs), sitePath);
    }

    /** The normalised value of a header line. */
    public String header(String name, String value, Refs refs, String sitePath) {
        return text(value, refs, sitePath);
    }

    /** A text with every local URL in it normalised. */
    public String text(String text, Map<Long, String> refs, String sitePath) {
        return text(text, Refs.ofIds(refs), sitePath);
    }

    /** A text with every local URL in it normalised. */
    public String text(String text, Refs refs, String sitePath) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int start = urlStart(text, i);
            if (start < 0) {
                out.append(text, i, text.length());
                break;
            }
            out.append(text, i, start);
            int end = start;
            while (end < text.length() && !isUrlEnd(text.charAt(end))) {
                end++;
            }
            out.append(url(text.substring(start, end), refs, sitePath));
            i = end;
        }
        return out.toString();
    }

    private JsonNode walk(JsonNode node, Refs refs, String sitePath, Kind kind) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            Kind own = objectKind(object, kind);
            for (Iterator<Map.Entry<String, JsonNode>> it = object.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                String key = field.getKey();
                JsonNode value = field.getValue();
                Kind idKind = ID_FIELDS.get(key);
                if (idKind != null && value.isIntegralNumber()) {
                    field.setValue(TextNode.valueOf(id(value.longValue(), idKind == Kind.ANY ? own : idKind, refs)));
                } else if (key.equals("additional_data") && value.isArray()) {
                    ArrayNode array = (ArrayNode) value;
                    for (int i = 0; i < array.size(); i++) {
                        if (array.get(i).isIntegralNumber()) {
                            array.set(i, TextNode.valueOf(id(array.get(i).longValue(), Kind.TERM, refs)));
                        } else {
                            array.set(i, walk(array.get(i), refs, sitePath, kind));
                        }
                    }
                } else if (key.equals("registered_date") && value.isTextual()) {
                    field.setValue(TextNode.valueOf("{date:" + shape(value.textValue()) + "}"));
                } else if (key.equals("_modified") && value.isTextual()) {
                    field.setValue(TextNode.valueOf("{stamp}"));
                } else if (key.equals("authentication")) {
                    field.setValue(TextNode.valueOf("{auth}"));
                } else {
                    field.setValue(walk(value, refs, sitePath, kind));
                }
            }
            return object;
        }
        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                array.set(i, walk(array.get(i), refs, sitePath, kind));
            }
            return array;
        }
        if (node.isTextual()) {
            String text = node.textValue();
            String normalised = text(text, refs, sitePath);
            return normalised.equals(text) ? node : JsonNodeFactory.instance.textNode(normalised);
        }
        return node;
    }

    /** A date's format: each digit as {@code d}, so dates of the same format compare equal and others differ. */
    static String shape(String date) {
        StringBuilder out = new StringBuilder(date.length());
        for (int i = 0; i < date.length(); i++) {
            char c = date.charAt(i);
            out.append(c >= '0' && c <= '9' ? 'd' : c);
        }
        return out.toString();
    }

    private static Kind objectKind(ObjectNode object, Kind fallback) {
        if (object.has("taxonomy")) {
            return Kind.TERM;
        }
        for (String hint : USER_HINTS) {
            if (object.has(hint)) {
                return Kind.USER;
            }
        }
        return fallback;
    }

    private static String id(long id, Kind kind, Refs refs) {
        String ref = refs.find(kind, id);
        return ref != null ? ref : "#?" + id;
    }

    /** The kind of the {@code /wp/v2/<base>} route in a path; ANY for other routes. */
    static Kind routeKind(String path) {
        if (path.contains("/wp/v2/users")) {
            return Kind.USER;
        }
        if (path.contains("/wp/v2/categories") || path.contains("/wp/v2/tags")) {
            return Kind.TERM;
        }
        if (path.contains("/wp/v2/posts") || path.contains("/wp/v2/pages") || path.contains("/wp/v2/media")) {
            return Kind.POST;
        }
        return Kind.ANY;
    }

    /** The start of the next local URL at or after {@code from}; -1 when there is none. */
    private static int urlStart(String text, int from) {
        int i = from;
        while (true) {
            int http = text.indexOf("http", i);
            if (http < 0) {
                return -1;
            }
            int after = text.startsWith("https://", http) ? http + 8 : text.startsWith("http://", http) ? http + 7 : -1;
            if (after > 0) {
                for (String host : LOCAL_HOSTS) {
                    if (text.startsWith(host, after)) {
                        int end = after + host.length();
                        if (end == text.length()
                                || "/:?#".indexOf(text.charAt(end)) >= 0
                                || isUrlEnd(text.charAt(end))) {
                            return http;
                        }
                    }
                }
            }
            i = http + 4;
        }
    }

    private static boolean isUrlEnd(char c) {
        return c <= ' ' || c == '"' || c == '\'' || c == '<' || c == '>' || c == '\\';
    }

    /** One local URL: {host}, {site}, then the ids in its path and id query parameters. */
    private static String url(String url, Refs refs, String sitePath) {
        int afterScheme = url.indexOf("//") + 2;
        int hostEnd = afterScheme;
        while (hostEnd < url.length() && "/?#".indexOf(url.charAt(hostEnd)) < 0) {
            hostEnd++;
        }
        StringBuilder out = new StringBuilder(url.substring(0, afterScheme)).append("{host}");
        String rest = url.substring(hostEnd);
        String site = sitePath.endsWith("/") ? sitePath.substring(0, sitePath.length() - 1) : sitePath;
        if (!site.isEmpty()
                && rest.startsWith(site)
                && (rest.length() == site.length() || "/?#".indexOf(rest.charAt(site.length())) >= 0)) {
            out.append("{site}");
            rest = rest.substring(site.length());
        }
        int query = rest.indexOf('?');
        int fragment = rest.indexOf('#');
        int pathEnd = query >= 0 ? query : fragment >= 0 ? fragment : rest.length();
        String path = rest.substring(0, pathEnd);
        out.append(path(path, refs));
        if (query >= 0) {
            int queryEnd = fragment > query ? fragment : rest.length();
            out.append('?').append(query(rest.substring(query + 1, queryEnd), refs, routeKind(path)));
            out.append(rest.substring(queryEnd));
        } else {
            out.append(rest.substring(pathEnd));
        }
        return out.toString();
    }

    private static String path(String path, Refs refs) {
        List<String> segments = new ArrayList<>();
        int i = 0;
        while (true) {
            int slash = path.indexOf('/', i);
            segments.add(path.substring(i, slash < 0 ? path.length() : slash));
            if (slash < 0) {
                break;
            }
            i = slash + 1;
        }
        StringBuilder out = new StringBuilder(path.length());
        for (int s = 0; s < segments.size(); s++) {
            if (s > 0) {
                out.append('/');
            }
            Kind kind = s > 0 ? baseKind(segments.get(s - 1)) : null;
            out.append(kind == null ? segments.get(s) : knownId(segments.get(s), kind, refs));
        }
        return out.toString();
    }

    private static Kind baseKind(String segment) {
        return switch (segment) {
            case "users" -> Kind.USER;
            case "categories", "tags" -> Kind.TERM;
            case "posts", "pages", "media", "blocks" -> Kind.POST;
            default -> null;
        };
    }

    private static String query(String query, Refs refs, Kind routeKind) {
        StringBuilder out = new StringBuilder(query.length());
        int i = 0;
        while (true) {
            int amp = query.indexOf('&', i);
            int end = amp < 0 ? query.length() : amp;
            String pair = query.substring(i, end);
            int equals = pair.indexOf('=');
            Kind kind = equals > 0 ? ID_QUERY_KEYS.get(baseKey(pair.substring(0, equals))) : null;
            if (kind != null) {
                out.append(pair, 0, equals + 1)
                        .append(idList(pair.substring(equals + 1), kind == Kind.ANY ? routeKind : kind, refs));
            } else {
                out.append(pair);
            }
            if (amp < 0) {
                break;
            }
            out.append('&');
            i = amp + 1;
        }
        return out.toString();
    }

    /** A query key without its bracket suffix, raw or percent-encoded ({@code include[0]}, {@code include%5B0%5D}). */
    private static String baseKey(String key) {
        int bracket = key.indexOf('[');
        int encoded = key.indexOf("%5B");
        if (encoded < 0) {
            encoded = key.indexOf("%5b");
        }
        int cut = bracket >= 0 && (encoded < 0 || bracket < encoded) ? bracket : encoded;
        return cut >= 0 ? key.substring(0, cut) : key;
    }

    /** A value that may be a comma list (raw or {@code %2C}); each item that is a known id becomes its ref. */
    private static String idList(String value, Kind kind, Refs refs) {
        StringBuilder out = new StringBuilder(value.length());
        int i = 0;
        while (i <= value.length()) {
            int comma = value.indexOf(',', i);
            int encoded = indexOfIgnoreCase(value, "%2C", i);
            int end = comma < 0 ? encoded : encoded < 0 ? comma : Math.min(comma, encoded);
            if (end < 0) {
                out.append(knownId(value.substring(i), kind, refs));
                break;
            }
            out.append(knownId(value.substring(i, end), kind, refs));
            int separator = end == comma ? 1 : 3;
            out.append(value, end, end + separator);
            i = end + separator;
        }
        return out.toString();
    }

    private static int indexOfIgnoreCase(String text, String needle, int from) {
        int upper = text.indexOf(needle, from);
        int lower = text.indexOf(needle.toLowerCase(java.util.Locale.ROOT), from);
        return upper < 0 ? lower : lower < 0 ? upper : Math.min(upper, lower);
    }

    /** The ref of an all-digit text that is a known id of the kind; the text itself otherwise. */
    private static String knownId(String text, Kind kind, Refs refs) {
        if (text.isEmpty() || text.length() > 18) {
            return text;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                return text;
            }
        }
        String ref = refs.find(kind, Long.parseLong(text));
        return ref != null ? ref : text;
    }
}
