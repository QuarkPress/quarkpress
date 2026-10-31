package org.quarkpress.restwp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.core.SiteNotResolvedException;
import org.quarkpress.restwp.Links;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.RestUrls;

/**
 * WordPress's rendering of a response's links: one list per relation in the order relations first appear, each link's
 * attributes followed by its {@code href} (an {@code href} attribute other than null is dropped); a {@code self} link also gets the methods its caller may use on it
 * ({@code targetHints.allow}, the {@code Allow} a GET of it would answer) unless it names them itself; relations below
 * {@code https://api.w.org/} are compacted to {@code wp:<name>} (moving after the others) and the {@code curies} entry
 * naming that prefix comes last.
 */
public final class LinkRenderer {

    /** The prefix of WordPress's link relations, and the curie that compacts them. */
    static final String API_RELATIONS = "https://api.w.org/";

    /** A renderer that resolves no link: no target hints, no embedding. */
    private static final LinkRenderer UNRESOLVED = new LinkRenderer(null, null);

    /** Where a link leads in this site's API: a route path and the query of the link. */
    public record Target(String route, ObjectNode query) {}

    private final RouteTable table;
    private final RestUrls urls;
    private String root;
    private String home;

    /**
     * @param table the routes links are matched against for their target hints
     * @param urls the site whose API root and home a link must start with to be resolved
     */
    public LinkRenderer(RouteTable table, RestUrls urls) {
        this.table = table;
        this.urls = urls;
    }

    /**
     * {@code links} rendered for {@code caller}: an empty object when there are none. A request the REST server
     * dispatched resolves self links against its routes for their target hints; any other request gets none.
     */
    public static ObjectNode render(Links links, RestRequest caller) {
        LinkRenderer renderer = caller instanceof ServedRequest served ? served.linkRenderer() : null;
        return (renderer == null ? UNRESOLVED : renderer).compact(links, caller);
    }

    /** {@link #render} with this renderer's routes and site. */
    public ObjectNode compact(Links links, RestRequest caller) {
        Map<String, ArrayNode> byRelation = new LinkedHashMap<>();
        for (Links.Link link : links.all()) {
            ObjectNode item = RestJson.NODES.objectNode();
            link.attributes().forEach((name, value) -> {
                // a set href attribute is dropped, as WordPress's add_link drops it: the link's own href comes after
                if (!name.equals("href") || value == null || value.isNull()) {
                    item.set(name, value);
                }
            });
            item.put("href", link.href());
            if (link.rel().equals("self") && !namesItsAllowedMethods(item.get("targetHints"))) {
                targetHints(link.href(), caller).ifPresent(hints -> item.set("targetHints", hints));
            }
            byRelation
                    .computeIfAbsent(link.rel(), rel -> RestJson.NODES.arrayNode())
                    .add(item);
        }
        Map<String, ArrayNode> compacted = new LinkedHashMap<>(byRelation);
        boolean curie = false;
        for (Map.Entry<String, ArrayNode> relation : byRelation.entrySet()) {
            Optional<String> name = compactName(relation.getKey());
            if (name.isPresent()) {
                compacted.remove(relation.getKey());
                compacted.put("wp:" + name.get(), relation.getValue());
                curie = true;
            }
        }
        ObjectNode rendered = RestJson.NODES.objectNode();
        compacted.forEach(rendered::set);
        if (curie) {
            rendered.putArray("curies")
                    .addObject()
                    .put("name", "wp")
                    .put("href", API_RELATIONS + "{rel}")
                    .put("templated", true);
        }
        return rendered;
    }

    /** A {@code targetHints} attribute with an {@code allow} entry, which WordPress keeps instead of computing one. */
    private static boolean namesItsAllowedMethods(JsonNode hints) {
        return hints != null
                && hints.isObject()
                && hints.get("allow") != null
                && !hints.get("allow").isNull();
    }

    /**
     * The name after {@code https://api.w.org/} as WordPress's curie pattern takes it: the characters after the first
     * occurrence of the prefix that is followed by something other than a line feed, up to the next line feed; empty
     * when there is none (as for the bare prefix).
     */
    static Optional<String> compactName(String relation) {
        if (!relation.startsWith(API_RELATIONS)) {
            return Optional.empty();
        }
        int from = 0;
        while (from >= 0) {
            int after = from + API_RELATIONS.length();
            if (after < relation.length() && relation.charAt(after) != '\n') {
                int end = relation.indexOf('\n', after);
                return Optional.of(relation.substring(after, end < 0 ? relation.length() : end));
            }
            from = relation.indexOf(API_RELATIONS, from + 1);
        }
        return Optional.empty();
    }

    /**
     * The methods {@code caller} may use on {@code href}, as WordPress's target hints: the link resolved to a route of
     * this site, matched for GET, its arguments (the link's query, the route's groups and the endpoint's defaults)
     * valid, and at least one method allowed by the route's endpoints' permissions for this caller; else none.
     */
    Optional<ObjectNode> targetHints(String href, RestRequest caller) {
        if (table == null) {
            return Optional.empty();
        }
        Optional<Target> target = target(href);
        if (target.isEmpty()) {
            return Optional.empty();
        }
        Optional<RouteTable.Match> found = table.match(target.get().route(), "GET");
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RouteTable.Match match = found.get();
        ObjectNode query = target.get().query();
        ParamSources sources = new ParamSources(
                RestJson.NODES.objectNode(),
                RestJson.NODES.objectNode(),
                query,
                match.urlParams(),
                RestServer.defaults(match));
        List<String> allowed;
        try {
            if (ArgChecks.check(match.endpoint().args(), sources).isPresent()) {
                return Optional.empty();
            }
            RestServer.Served request = new RestServer.Served(
                    "GET",
                    match.matchedRoute(),
                    match.endpoint(),
                    target.get().route(),
                    sources,
                    query,
                    caller.identity(),
                    caller::user,
                    urls,
                    this);
            allowed = AllowHeader.methods(match.matchedRoute(), request);
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
        if (allowed.isEmpty()) {
            return Optional.empty();
        }
        ObjectNode hints = RestJson.NODES.objectNode();
        ArrayNode allow = hints.putArray("allow");
        allowed.forEach(allow::add);
        return Optional.of(hints);
    }

    /**
     * Where {@code href} leads in this site's API, as WordPress turns a URL into a request: below the API root, the
     * path after it (up to a {@code ?} or {@code #}); else, below the site's home, a non-empty {@code rest_route} of
     * its query (which then leaves the query). The query is the URL's, parsed as PHP parses one. A URL of another host
     * or site leads nowhere (WordPress would dispatch another host's {@code rest_route} on this site).
     */
    public Optional<Target> target(String href) {
        if (urls == null || !resolveSite()) {
            return Optional.empty();
        }
        int hash = href.indexOf('#');
        int question = href.indexOf('?');
        boolean hasQuery = question >= 0 && (hash < 0 || question < hash);
        ObjectNode query = hasQuery
                ? PhpQuery.parse(href.substring(question + 1, hash < 0 ? href.length() : hash))
                : RestJson.NODES.objectNode();
        String route;
        if (href.startsWith(root)) {
            int start = root.length() - 1;
            int end = href.length();
            for (int i = start; i < href.length(); i++) {
                char c = href.charAt(i);
                if (c == '?' || c == '#') {
                    end = i;
                    break;
                }
            }
            route = href.substring(start, end);
            if (route.startsWith("//")) {
                // PHP would read what follows the two slashes as a host
                return Optional.empty();
            }
        } else if (href.startsWith(home)
                && query.get("rest_route") != null
                && query.get("rest_route").isTextual()) {
            route = query.get("rest_route").textValue();
            query.remove("rest_route");
        } else {
            return Optional.empty();
        }
        if (route.isEmpty() || route.equals("0")) {
            return Optional.empty();
        }
        return Optional.of(new Target(route, query));
    }

    /** Reads the site's API root and home once; false when no site is resolved. */
    private boolean resolveSite() {
        if (root == null) {
            try {
                root = urls.root();
                home = urls.home();
            } catch (SiteNotResolvedException unresolved) {
                return false;
            }
        }
        return true;
    }
}
