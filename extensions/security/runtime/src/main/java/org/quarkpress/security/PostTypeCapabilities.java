package org.quarkpress.security;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The capability names of a post type, built as WordPress builds them from a capability type: meta names from the
 * singular ({@code edit_book}), primitive names from the plural ({@code edit_books}, {@code edit_others_books}, ...),
 * and, only when the type maps meta capabilities, {@code read} and the private, published and others' delete and edit
 * names (null otherwise; required when {@code mapMetaCap} is true). Entries of {@code overrides} (WordPress's names as keys, such as {@code read} or
 * {@code create_posts}) replace computed ones; {@code create_posts} defaults to the {@code edit_posts} name.
 *
 * @param names every capability name of the type keyed by WordPress's generic name, in the order WordPress's capability
 *     object has them (the computed names, the overrides' new names after them, then {@code create_posts} when not
 *     given); each named component above equals its entry (or is null when there is none)
 */
public record PostTypeCapabilities(
        String name,
        boolean mapMetaCap,
        String editPost,
        String readPost,
        String deletePost,
        String editPosts,
        String editOthersPosts,
        String deletePosts,
        String publishPosts,
        String readPrivatePosts,
        String read,
        String deletePrivatePosts,
        String deletePublishedPosts,
        String deleteOthersPosts,
        String editPrivatePosts,
        String editPublishedPosts,
        String createPosts,
        String editPage,
        String readPage,
        String deletePage,
        Map<String, String> names) {

    public static final PostTypeCapabilities POST = of("post", "post", "posts", true, Map.of());

    public static final PostTypeCapabilities PAGE = of("page", "page", "pages", true, Map.of());

    public static final PostTypeCapabilities ATTACHMENT =
            of("attachment", "post", "posts", true, Map.of("create_posts", "upload_files"));

    /** Reusable blocks: reading needs {@code edit_posts}, creating {@code publish_posts}. */
    public static final PostTypeCapabilities BLOCK = of(
            "wp_block",
            "block",
            "blocks",
            true,
            Map.ofEntries(
                    Map.entry("read", "edit_posts"),
                    Map.entry("create_posts", "publish_posts"),
                    Map.entry("edit_posts", "edit_posts"),
                    Map.entry("edit_published_posts", "edit_published_posts"),
                    Map.entry("delete_published_posts", "delete_published_posts"),
                    Map.entry("delete_posts", "delete_posts"),
                    Map.entry("edit_others_posts", "edit_others_posts"),
                    Map.entry("delete_others_posts", "delete_others_posts")));

    public PostTypeCapabilities {
        Objects.requireNonNull(name, "name");
        names = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(names, "names")));
        Objects.requireNonNull(editPost, "editPost");
        Objects.requireNonNull(readPost, "readPost");
        Objects.requireNonNull(deletePost, "deletePost");
        Objects.requireNonNull(editPosts, "editPosts");
        Objects.requireNonNull(editOthersPosts, "editOthersPosts");
        Objects.requireNonNull(deletePosts, "deletePosts");
        Objects.requireNonNull(publishPosts, "publishPosts");
        Objects.requireNonNull(readPrivatePosts, "readPrivatePosts");
        Objects.requireNonNull(createPosts, "createPosts");
        if (mapMetaCap) {
            Objects.requireNonNull(read, "read");
            Objects.requireNonNull(deletePrivatePosts, "deletePrivatePosts");
            Objects.requireNonNull(deletePublishedPosts, "deletePublishedPosts");
            Objects.requireNonNull(deleteOthersPosts, "deleteOthersPosts");
            Objects.requireNonNull(editPrivatePosts, "editPrivatePosts");
            Objects.requireNonNull(editPublishedPosts, "editPublishedPosts");
        }
        String[][] named = {
            {"edit_post", editPost},
            {"read_post", readPost},
            {"delete_post", deletePost},
            {"edit_posts", editPosts},
            {"edit_others_posts", editOthersPosts},
            {"delete_posts", deletePosts},
            {"publish_posts", publishPosts},
            {"read_private_posts", readPrivatePosts},
            {"read", read},
            {"delete_private_posts", deletePrivatePosts},
            {"delete_published_posts", deletePublishedPosts},
            {"delete_others_posts", deleteOthersPosts},
            {"edit_private_posts", editPrivatePosts},
            {"edit_published_posts", editPublishedPosts},
            {"create_posts", createPosts},
            {"edit_page", editPage},
            {"read_page", readPage},
            {"delete_page", deletePage}
        };
        for (String[] entry : named) {
            if (!Objects.equals(entry[1], names.get(entry[0]))) {
                throw new IllegalArgumentException("capability name " + entry[0] + " of post type " + name + " is "
                        + entry[1] + " but the names map has " + names.get(entry[0]));
            }
        }
    }

    public static PostTypeCapabilities of(
            String name, String singular, String plural, boolean mapMetaCap, Map<String, String> overrides) {
        Map<String, String> caps = new LinkedHashMap<>();
        caps.put("edit_post", "edit_" + singular);
        caps.put("read_post", "read_" + singular);
        caps.put("delete_post", "delete_" + singular);
        caps.put("edit_posts", "edit_" + plural);
        caps.put("edit_others_posts", "edit_others_" + plural);
        caps.put("delete_posts", "delete_" + plural);
        caps.put("publish_posts", "publish_" + plural);
        caps.put("read_private_posts", "read_private_" + plural);
        if (mapMetaCap) {
            caps.put("read", "read");
            caps.put("delete_private_posts", "delete_private_" + plural);
            caps.put("delete_published_posts", "delete_published_" + plural);
            caps.put("delete_others_posts", "delete_others_" + plural);
            caps.put("edit_private_posts", "edit_private_" + plural);
            caps.put("edit_published_posts", "edit_published_" + plural);
        }
        caps.putAll(overrides);
        caps.putIfAbsent("create_posts", caps.get("edit_posts"));
        return new PostTypeCapabilities(
                name,
                mapMetaCap,
                caps.get("edit_post"),
                caps.get("read_post"),
                caps.get("delete_post"),
                caps.get("edit_posts"),
                caps.get("edit_others_posts"),
                caps.get("delete_posts"),
                caps.get("publish_posts"),
                caps.get("read_private_posts"),
                caps.get("read"),
                caps.get("delete_private_posts"),
                caps.get("delete_published_posts"),
                caps.get("delete_others_posts"),
                caps.get("edit_private_posts"),
                caps.get("edit_published_posts"),
                caps.get("create_posts"),
                caps.get("edit_page"),
                caps.get("read_page"),
                caps.get("delete_page"),
                caps);
    }

    /**
     * The type's own name for a meta capability, which a type that does not map meta capabilities requires directly:
     * its {@code edit_post}, {@code read_post} or {@code delete_post} name; the page forms ({@code edit_page}, {@code read_page}, {@code delete_page}) as the type
     * sets them, or the empty name when it does not; any other name as given.
     */
    public String metaName(String metaCapability) {
        return switch (metaCapability) {
            case "edit_post" -> editPost;
            case "read_post" -> readPost;
            case "delete_post" -> deletePost;
            // WordPress's capability object has no such entry unless the type sets one: its null requires nothing
            // any role can hold (only network administrators pass), so the empty name stands for it.
            case "edit_page" -> editPage == null ? "" : editPage;
            case "read_page" -> readPage == null ? "" : readPage;
            case "delete_page" -> deletePage == null ? "" : deletePage;
            default -> metaCapability;
        };
    }
}
