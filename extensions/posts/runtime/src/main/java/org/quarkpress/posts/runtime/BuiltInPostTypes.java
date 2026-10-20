package org.quarkpress.posts.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.quarkpress.core.PostTypeDefinition;

/** WordPress's built-in post types that QuarkPress registers, with the arguments WordPress registers them with. */
public final class BuiltInPostTypes {

    private BuiltInPostTypes() {}

    public static List<PostTypeDefinition> all() {
        return List.of(post(), page(), attachment(), block());
    }

    private static PostTypeDefinition post() {
        return PostTypeDefinition.builder("post")
                .labels(ordered("name_admin_bar", "Post"))
                .publicly(true)
                .capabilityType("post")
                .mapMetaCap(true)
                .hierarchical(false)
                .supports(List.of(
                        "title",
                        "editor",
                        "author",
                        "thumbnail",
                        "excerpt",
                        "trackbacks",
                        "custom-fields",
                        "comments",
                        "revisions",
                        "post-formats"))
                .showInRest(true)
                .restBase("posts")
                .builtIn()
                .build();
    }

    private static PostTypeDefinition page() {
        return PostTypeDefinition.builder("page")
                .labels(ordered("name_admin_bar", "Page"))
                .publicly(true)
                .publiclyQueryable(false)
                .capabilityType("page")
                .mapMetaCap(true)
                .hierarchical(true)
                .supports(List.of(
                        "title",
                        "editor",
                        "author",
                        "thumbnail",
                        "page-attributes",
                        "custom-fields",
                        "comments",
                        "revisions"))
                .showInRest(true)
                .restBase("pages")
                .builtIn()
                .build();
    }

    private static PostTypeDefinition attachment() {
        return PostTypeDefinition.builder("attachment")
                .labels(ordered(
                        "name", "Media",
                        "name_admin_bar", "Media",
                        "add_new", "Add Media File",
                        "add_new_item", "Add Media File",
                        "edit_item", "Edit Media",
                        "view_item", "View Media File",
                        "attributes", "Attachment Attributes"))
                .publicly(true)
                .showUi(true)
                .capabilityType("post")
                .capabilities(ordered("create_posts", "upload_files"))
                .mapMetaCap(true)
                .hierarchical(false)
                .showInNavMenus(false)
                .supports(List.of("title", "author", "comments"))
                .showInRest(true)
                .restBase("media")
                .builtIn()
                .build();
    }

    private static PostTypeDefinition block() {
        return PostTypeDefinition.builder("wp_block")
                .labels(ordered(
                        "name", "Patterns",
                        "singular_name", "Pattern",
                        "add_new", "Add Pattern",
                        "add_new_item", "Add Pattern",
                        "new_item", "New Pattern",
                        "edit_item", "Edit Block Pattern",
                        "view_item", "View Pattern",
                        "view_items", "View Patterns",
                        "all_items", "All Patterns",
                        "search_items", "Search Patterns",
                        "not_found", "No patterns found.",
                        "not_found_in_trash", "No patterns found in Trash.",
                        "filter_items_list", "Filter patterns list",
                        "items_list_navigation", "Patterns list navigation",
                        "items_list", "Patterns list",
                        "item_published", "Pattern published.",
                        "item_published_privately", "Pattern published privately.",
                        "item_reverted_to_draft", "Pattern reverted to draft.",
                        "item_scheduled", "Pattern scheduled.",
                        "item_updated", "Pattern updated."))
                .publicly(false)
                .showUi(true)
                .showInMenu(false)
                .capabilityType("block")
                .capabilities(ordered(
                        "read", "edit_posts",
                        "create_posts", "publish_posts",
                        "edit_posts", "edit_posts",
                        "edit_published_posts", "edit_published_posts",
                        "delete_published_posts", "delete_published_posts",
                        "delete_posts", "delete_posts",
                        "edit_others_posts", "edit_others_posts",
                        "delete_others_posts", "delete_others_posts"))
                .mapMetaCap(true)
                .supports(List.of("title", "excerpt", "editor", "revisions", "custom-fields"))
                .showInRest(true)
                .restBase("blocks")
                .builtIn()
                .build();
    }

    private static Map<String, String> ordered(String... keysAndValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }
}
