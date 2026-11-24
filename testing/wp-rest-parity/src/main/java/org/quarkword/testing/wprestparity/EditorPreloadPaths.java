package org.quarkword.testing.wprestparity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The requests WordPress's edit screen preloads for the block editor that QuarkWord serves, in WordPress's order and
 * with WordPress's key forms (Part G fact v08: the paths verbatim, parameters unsorted, no {@code _locale}), minus the
 * seven QuarkWord does not serve (the templates OPTIONS, the four global-styles reads and the two template lookups).
 * Both sides of the comparison derive the preload map from this list: WordPress's side sends each entry as the caller
 * and keeps the 200s; QuarkWord's side asks its editor route, whose map the parity run compares key by key.
 */
public final class EditorPreloadPaths {

    /** The editor route both sides address: {@code /wp-json/quarkword/v1/editor/<type>/<id>}. */
    public static final String EDITOR_ROUTE = "/wp-json/quarkword/v1/editor/";

    /** The key of the map holding the OPTIONS entries. */
    public static final String OPTIONS = "OPTIONS";

    /** The {@code _fields} of the site index entry, the client's string verbatim. */
    static final String INDEX_FIELDS = "description,gmt_offset,home,image_max_bit_depth,image_sizes,"
            + "image_size_threshold,image_strip_meta,name,site_icon,site_icon_url,site_logo,timezone_string,url,"
            + "page_for_posts,page_on_front,show_on_front";

    /** One preloaded request: its method ({@code GET} or {@code OPTIONS}) and its path, the map's key. */
    public record Entry(String method, String path) {
        public Entry {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(path, "path");
        }

        public boolean options() {
            return method.equals(OPTIONS);
        }
    }

    /** The post an editor target names: its type and its id. */
    public record Target(String type, long id) {}

    private EditorPreloadPaths() {}

    /**
     * The served entries for a post of {@code type} with the REST base {@code restBase} (under {@code wp/v2}), in
     * WordPress's order; the author entry only when {@code author} is positive (the caller passes 0 for a type
     * without author support).
     */
    public static List<Entry> forPost(String type, long id, long author, String restBase) {
        String base = "/wp/v2/" + restBase;
        List<Entry> entries = new ArrayList<>();
        entries.add(get("/wp/v2/types?context=view"));
        entries.add(get("/wp/v2/taxonomies?context=view"));
        entries.add(get(base + "/" + id + "?context=edit"));
        entries.add(get("/wp/v2/types/" + type + "?context=edit"));
        entries.add(get("/wp/v2/users/me"));
        entries.add(options("/wp/v2/media"));
        entries.add(options("/wp/v2/pages"));
        entries.add(options("/wp/v2/blocks"));
        entries.add(get(base + "/" + id + "/autosaves?context=edit"));
        entries.add(get("/wp/v2/settings"));
        entries.add(options("/wp/v2/settings"));
        entries.add(get("/wp/v2/themes?context=edit&status=active"));
        entries.add(get("/wp/v2/block-patterns/categories"));
        entries.add(get("/?_fields=" + INDEX_FIELDS));
        entries.add(get("/wp/v2/taxonomies?context=edit"));
        entries.add(options(base));
        if (author > 0) {
            entries.add(get("/wp/v2/users/" + author + "?context=view&_fields=id,name"));
        }
        return List.copyOf(entries);
    }

    /**
     * The top-level keys of the map the entries produce when every answer is 200: the GET paths in order, with the
     * single {@code OPTIONS} key where the first OPTIONS entry falls.
     */
    public static List<String> keys(List<Entry> entries) {
        List<String> keys = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.options()) {
                if (!keys.contains(OPTIONS)) {
                    keys.add(OPTIONS);
                }
            } else {
                keys.add(entry.path());
            }
        }
        return List.copyOf(keys);
    }

    /** The keys under {@code OPTIONS}: the OPTIONS paths in order. */
    public static List<String> optionsKeys(List<Entry> entries) {
        List<String> keys = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.options()) {
                keys.add(entry.path());
            }
        }
        return List.copyOf(keys);
    }

    /** The REST base of a post type both sides register. */
    public static String restBase(String type) {
        return switch (type) {
            case "post" -> "posts";
            case "page" -> "pages";
            case "attachment" -> "media";
            case "wp_block" -> "blocks";
            case "book" -> "books";
            default -> type;
        };
    }

    /** The editor route's target for a post of {@code type} named by {@code post}, an id or a ref in braces. */
    public static String editorTarget(String type, String post) {
        return EDITOR_ROUTE + type + "/" + post;
    }

    /** Whether a request target is the editor route (a preload comparison). */
    public static boolean isEditorTarget(String target) {
        return target.startsWith(EDITOR_ROUTE);
    }

    /** The type and id an editor target names, once its ref is resolved; empty for any other target. */
    public static Optional<Target> parse(String target) {
        if (!isEditorTarget(target)) {
            return Optional.empty();
        }
        String rest = target.substring(EDITOR_ROUTE.length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            return Optional.empty();
        }
        String id = rest.substring(slash + 1);
        for (int i = 0; i < id.length(); i++) {
            if (id.charAt(i) < '0' || id.charAt(i) > '9') {
                return Optional.empty();
            }
        }
        if (id.length() > 18) {
            return Optional.empty();
        }
        return Optional.of(new Target(rest.substring(0, slash), Long.parseLong(id)));
    }

    private static Entry get(String path) {
        return new Entry("GET", path);
    }

    private static Entry options(String path) {
        return new Entry(OPTIONS, path);
    }
}
