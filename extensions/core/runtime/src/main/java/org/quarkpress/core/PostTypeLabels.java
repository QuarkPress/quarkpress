package org.quarkpress.core;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The labels of a post type as WordPress derives them: the given labels, names derived from the {@code name} and
 * {@code singular_name} labels (or the {@code label} argument), then WordPress's English defaults for a flat type
 * ("Posts") or a hierarchical one ("Pages"). A default WordPress leaves unset ({@code parent_item_colon} of a flat
 * type) is absent from the result.
 */
final class PostTypeLabels {

    /** Key, flat default, hierarchical default; in WordPress's order, the menu name last. */
    private static final String[][] DEFAULTS = {
        {"name", "Posts", "Pages"},
        {"singular_name", "Post", "Page"},
        {"add_new", "Add", "Add"},
        {"add_new_item", "Add Post", "Add Page"},
        {"edit_item", "Edit Post", "Edit Page"},
        {"new_item", "New Post", "New Page"},
        {"view_item", "View Post", "View Page"},
        {"view_items", "View Posts", "View Pages"},
        {"search_items", "Search Posts", "Search Pages"},
        {"not_found", "No posts found.", "No pages found."},
        {"not_found_in_trash", "No posts found in Trash.", "No pages found in Trash."},
        {"parent_item_colon", null, "Parent Page:"},
        {"all_items", "All Posts", "All Pages"},
        {"archives", "Post Archives", "Page Archives"},
        {"attributes", "Post Attributes", "Page Attributes"},
        {"insert_into_item", "Insert into post", "Insert into page"},
        {"uploaded_to_this_item", "Uploaded to this post", "Uploaded to this page"},
        {"featured_image", "Featured image", "Featured image"},
        {"set_featured_image", "Set featured image", "Set featured image"},
        {"remove_featured_image", "Remove featured image", "Remove featured image"},
        {"use_featured_image", "Use as featured image", "Use as featured image"},
        {"filter_items_list", "Filter posts list", "Filter pages list"},
        {"filter_by_date", "Filter by date", "Filter by date"},
        {"items_list_navigation", "Posts list navigation", "Pages list navigation"},
        {"items_list", "Posts list", "Pages list"},
        {"item_published", "Post published.", "Page published."},
        {"item_published_privately", "Post published privately.", "Page published privately."},
        {"item_reverted_to_draft", "Post reverted to draft.", "Page reverted to draft."},
        {"item_trashed", "Post trashed.", "Page trashed."},
        {"item_scheduled", "Post scheduled.", "Page scheduled."},
        {"item_updated", "Post updated.", "Page updated."},
        {"item_link", "Post Link", "Page Link"},
        {"item_link_description", "A link to a post.", "A link to a page."},
        {"menu_name", "Posts", "Pages"}
    };

    private static final Set<String> DEFAULT_KEYS = defaultKeys();

    private PostTypeLabels() {}

    static Map<String, String> of(String key, boolean hierarchical, Optional<String> label, Map<String, String> given) {
        Map<String, String> own = new LinkedHashMap<>(given);
        if (label.isPresent() && phpEmpty(own.get("name"))) {
            own.put("name", label.get());
        }
        if (!own.containsKey("singular_name") && own.containsKey("name")) {
            own.put("singular_name", own.get("name"));
        }
        if (!own.containsKey("name_admin_bar")) {
            own.put("name_admin_bar", own.getOrDefault("singular_name", key));
        }
        if (!own.containsKey("menu_name") && own.containsKey("name")) {
            own.put("menu_name", own.get("name"));
        }
        if (!own.containsKey("all_items") && own.containsKey("menu_name")) {
            own.put("all_items", own.get("menu_name"));
        }
        if (!own.containsKey("archives") && own.containsKey("all_items")) {
            own.put("archives", own.get("all_items"));
        }
        Map<String, String> labels = new LinkedHashMap<>();
        for (String[] row : DEFAULTS) {
            String value = own.containsKey(row[0]) ? own.get(row[0]) : row[hierarchical ? 2 : 1];
            if (value != null) {
                labels.put(row[0], value);
            }
        }
        for (Map.Entry<String, String> entry : own.entrySet()) {
            if (!DEFAULT_KEYS.contains(entry.getKey())) {
                labels.put(entry.getKey(), entry.getValue());
            }
        }
        if (!own.containsKey("template_name") && own.containsKey("singular_name")) {
            labels.put("template_name", "Single item: " + own.get("singular_name"));
        }
        return labels;
    }

    /** PHP's {@code empty()} for an optional text: absent, "" and "0". */
    private static boolean phpEmpty(String text) {
        return text == null || text.isEmpty() || text.equals("0");
    }

    private static Set<String> defaultKeys() {
        Set<String> keys = new HashSet<>();
        for (String[] row : DEFAULTS) {
            keys.add(row[0]);
        }
        return Set.copyOf(keys);
    }
}
