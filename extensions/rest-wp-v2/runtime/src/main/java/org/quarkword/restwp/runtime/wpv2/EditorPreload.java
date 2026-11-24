package org.quarkword.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import org.quarkword.core.UserId;
import org.quarkword.posts.PostSnapshot;
import org.quarkword.restwp.RestRequest;
import org.quarkword.restwp.runtime.InternalAnswer;
import org.quarkword.restwp.runtime.RestJson;
import org.quarkword.restwp.runtime.RestServer;

/**
 * The apiFetch preload map of the editor route: the requests WordPress's edit screen answers before the block editor's
 * first request, each dispatched through the REST server as the caller and kept only when it answers 200, keyed by
 * WordPress's paths as the screen prints them (parameters in WordPress's order, no locale; the client normalizes both
 * sides on lookup). A GET entry is {@code {"body": <the answer with its links>, "headers": {...}}} under its path; the
 * OPTIONS entries sit under the one {@code "OPTIONS"} key, created where the first of them falls. {@code headers}
 * holds only the answer's own headers in the order it set them ({@code Link} then {@code Allow} on a post item,
 * {@code X-WP-Total} and {@code X-WP-TotalPages} as numbers then {@code Allow} on a paged collection, else
 * {@code Allow}); when it set none, {@code []}, as PHP prints an empty array.
 *
 * <p>Not listed: the templates OPTIONS, the global-styles reads and the template lookups, routes QuarkWord does not
 * serve (the admin shell supplies what the editor needs of them).
 */
@ApplicationScoped
public class EditorPreload {

    private static final String GET = "GET";

    private static final String OPTIONS = "OPTIONS";

    /** The site index fields the editor reads, the client's string verbatim. */
    private static final String INDEX_FIELDS = "description,gmt_offset,home,image_max_bit_depth,image_sizes,"
            + "image_size_threshold,image_strip_meta,name,site_icon,site_icon_url,site_logo,timezone_string,url,"
            + "page_for_posts,page_on_front,show_on_front";

    /** The headers whose values the screen prints as numbers. */
    private static final List<String> NUMERIC_HEADERS = List.of("X-WP-Total", "X-WP-TotalPages");

    /** One request to preload: {@code GET} or {@code OPTIONS} of a route path with its query, the map's key. */
    private record Entry(String method, String target) {}

    @Inject
    RestServer server;

    /**
     * The preload map for {@code post}, a post of the type {@code routes} serve, for the caller of {@code request}:
     * the entries in WordPress's order, the author entry only when the type supports authors and the post has one.
     */
    public ObjectNode of(PostRoutes routes, PostSnapshot post, RestRequest request) {
        ObjectNode map = RestJson.NODES.objectNode();
        ObjectNode options = null;
        for (Entry entry : entries(routes, post)) {
            InternalAnswer answer = server.dispatchInternal(entry.method(), entry.target(), request.secure());
            if (answer.status() != 200) {
                continue;
            }
            ObjectNode preloaded = RestJson.NODES.objectNode();
            preloaded.set("body", answer.data());
            preloaded.set("headers", headers(answer));
            if (entry.method().equals(OPTIONS)) {
                if (options == null) {
                    options = map.putObject(OPTIONS);
                }
                options.set(entry.target(), preloaded);
            } else {
                map.set(entry.target(), preloaded);
            }
        }
        return map;
    }

    /** The requests WordPress's screen preloads that QuarkWord serves, in WordPress's order. */
    private static List<Entry> entries(PostRoutes routes, PostSnapshot post) {
        String base = routes.collection();
        String item = routes.item(post.id());
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry(GET, "/wp/v2/types?context=view"));
        entries.add(new Entry(GET, "/wp/v2/taxonomies?context=view"));
        entries.add(new Entry(GET, item + "?context=edit"));
        entries.add(new Entry(GET, "/wp/v2/types/" + routes.key() + "?context=edit"));
        entries.add(new Entry(GET, "/wp/v2/users/me"));
        entries.add(new Entry(OPTIONS, "/wp/v2/media"));
        entries.add(new Entry(OPTIONS, "/wp/v2/pages"));
        entries.add(new Entry(OPTIONS, "/wp/v2/blocks"));
        entries.add(new Entry(GET, item + "/autosaves?context=edit"));
        entries.add(new Entry(GET, "/wp/v2/settings"));
        entries.add(new Entry(OPTIONS, "/wp/v2/settings"));
        entries.add(new Entry(GET, "/wp/v2/themes?context=edit&status=active"));
        entries.add(new Entry(GET, "/wp/v2/block-patterns/categories"));
        entries.add(new Entry(GET, "/?_fields=" + INDEX_FIELDS));
        entries.add(new Entry(GET, "/wp/v2/taxonomies?context=edit"));
        entries.add(new Entry(OPTIONS, base));
        if (routes.supports("author")) {
            long author = post.author().map(UserId::value).orElse(0L);
            if (author > 0) {
                entries.add(new Entry(GET, "/wp/v2/users/" + author + "?context=view&_fields=id,name"));
            }
        }
        return entries;
    }

    /**
     * The answer's own headers as the screen prints them: each value a string (several lines joined as one header
     * line is), the totals as numbers; an empty set as {@code []}.
     */
    private static JsonNode headers(InternalAnswer answer) {
        if (answer.headers().isEmpty()) {
            return RestJson.NODES.arrayNode();
        }
        ObjectNode headers = RestJson.NODES.objectNode();
        answer.headers().forEach((name, values) -> {
            String value = String.join(", ", values);
            if (NUMERIC_HEADERS.contains(name) && isDigits(value)) {
                headers.put(name, Long.parseLong(value));
            } else {
                headers.put(name, value);
            }
        });
        return headers;
    }

    private static boolean isDigits(String text) {
        if (text.isEmpty() || text.length() > 18) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }
}
