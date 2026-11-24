package org.quarkword.front.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.quarkword.core.PostTypeDefinition;
import org.quarkword.core.TaxonomyDefinition;
import org.quarkword.front.QueryVars;
import org.quarkword.posts.PostSnapshot;

/**
 * WordPress's request parser: the request path below the site's home path is matched against the rewrite table (in
 * plain mode the path is ignored), the query string's public variables win over the rule's, a post type's query
 * variable also sets the type and the name, taxonomy values keep their pluses, a non-queryable taxonomy or post type
 * is dropped, numeric slugs are told apart from date archives, and a path no rule took is marked not found; a given
 * {@code error} counts only in plain mode. Only the query string is read: front-end requests are GET or HEAD.
 */
public final class RequestParser {

    private static final List<String> PAGE_TYPES = List.of("page", "attachment");
    private static final List<String> POST_TYPES = List.of("post", "attachment");
    private static final String INDEX = "index.php";
    private static final String ADMIN = "wp-admin";

    /** The registered post types and taxonomies the public variables are read from. */
    public record Registries(List<PostTypeDefinition> postTypes, List<TaxonomyDefinition> taxonomies) {
        public Registries {
            postTypes = List.copyOf(postTypes);
            taxonomies = List.copyOf(taxonomies);
        }
    }

    private RequestParser() {}

    /**
     * The query variables of a request for {@code rawPath} (the request URI's path, percent-encoded as sent) with the
     * decoded {@code query} parameters (the last value of a repeated name counts), on a site whose {@code home} URL
     * has the path {@code homePath} (empty at the host root).
     */
    public static QueryVars parse(
            String rawPath,
            Map<String, List<String>> query,
            Structure structure,
            RewriteRules rules,
            Registries registries,
            PagePaths pages,
            String homePath) {
        Objects.requireNonNull(rawPath, "rawPath");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(structure, "structure");
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(registries, "registries");
        Objects.requireNonNull(pages, "pages");
        Objects.requireNonNull(homePath, "homePath");
        PublicQueryVars publicVars = PublicQueryVars.of(registries.postTypes(), registries.taxonomies());

        Map<String, String> rewrite = Map.of();
        boolean notFound = false;
        if (!rules.isEmpty()) {
            String request = requestPath(rawPath, homePath);
            if (!request.isEmpty()) {
                Optional<RewriteRules.Match> match = rules.match(
                        request,
                        candidate -> !candidate.rule().pagenameCapture()
                                || !structure.verbosePageRules()
                                || pageAccepts(pages, candidate.vars().get("pagename")));
                if (match.isPresent()) {
                    rewrite = match.get().vars();
                } else if (!request.equals(ADMIN) && !request.startsWith(ADMIN + "/")) {
                    notFound = true;
                }
            }
        }

        Map<String, String> vars = new LinkedHashMap<>();
        Set<String> fromQuery = new LinkedHashSet<>();
        Set<String> fromRules = new LinkedHashSet<>();
        // with a rewrite table, WordPress reserves "error" for its own not-found mark and ignores a given one
        boolean errorReadable = rules.isEmpty();
        for (String name : publicVars.names()) {
            Optional<String> given = name.equals("error") && !errorReadable ? Optional.empty() : last(query.get(name));
            if (given.isPresent()) {
                vars.put(name, given.get());
                fromQuery.add(name);
            } else if (rewrite.containsKey(name)) {
                vars.put(name, rewrite.get(name));
                fromRules.add(name);
            }
        }

        // a type's variable expands only when its value is not empty as PHP counts it ("" and "0" are empty)
        for (String name : new ArrayList<>(vars.keySet())) {
            Optional<PostTypeDefinition> type = publicVars.postType(name);
            if (type.isPresent() && !phpEmpty(vars.get(name))) {
                vars.put("post_type", type.get().key());
                vars.put("name", vars.get(name));
                Set<String> source = fromQuery.contains(name) ? fromQuery : fromRules;
                source.add("post_type");
                source.add("name");
            }
        }

        for (String name : publicVars.taxonomyVars()) {
            if (vars.containsKey(name)) {
                vars.put(name, vars.get(name).replace(' ', '+'));
            }
        }
        String taxonomy = vars.get("taxonomy");
        if (taxonomy != null
                && publicVars.registeredTaxonomy(taxonomy)
                && !publicVars.publiclyQueryableTaxonomy(taxonomy)) {
            vars.remove("taxonomy");
            vars.remove("term");
        }
        String postType = vars.get("post_type");
        if (postType != null && !publicVars.publiclyQueryableType(postType)) {
            vars.remove("post_type");
        }

        QueryVars result = QueryVars.of(vars);
        result = NumericSlugConflicts.resolve(
                result,
                structure,
                path -> pages.find(path, POST_TYPES),
                post -> NumericSlugConflicts.pages(post.content()),
                pages.zone());
        if (notFound) {
            result = result.with("error", "404");
        }
        fromQuery.retainAll(result.asMap().keySet());
        fromRules.retainAll(result.asMap().keySet());
        return result.fromQueryString(fromQuery).fromRewrite(fromRules);
    }

    /**
     * The request path WordPress matches: the raw path without its slashes, the home path removed from its start
     * (ignoring case), {@code index.php} alone meaning the home and an {@code index.php/} prefix removed.
     */
    static String requestPath(String rawPath, String homePath) {
        String path = rawPath;
        int queryStart = path.indexOf('?');
        if (queryStart >= 0) {
            path = path.substring(0, queryStart);
        }
        path = trimSlashes(path);
        String home = trimSlashes(homePath);
        if (!home.isEmpty() && path.regionMatches(true, 0, home, 0, home.length())) {
            path = trimSlashes(path.substring(home.length()));
        }
        if (path.equals(INDEX)) {
            return "";
        }
        if (path.startsWith(INDEX + "/")) {
            path = trimSlashes(path.substring(INDEX.length() + 1));
        }
        return path;
    }

    private static boolean pageAccepts(PagePaths pages, String path) {
        if (path == null) {
            return true;
        }
        Optional<PostSnapshot> page = pages.find(path, PAGE_TYPES);
        return page.isPresent()
                && !page.get().status().equals("trash")
                && !page.get().status().equals("auto-draft");
    }

    private static boolean phpEmpty(String value) {
        return value.isEmpty() || value.equals("0");
    }

    private static Optional<String> last(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(values.get(values.size() - 1));
    }

    private static String trimSlashes(String path) {
        int start = 0;
        int end = path.length();
        while (start < end && path.charAt(start) == '/') {
            start++;
        }
        while (end > start && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(start, end);
    }
}
