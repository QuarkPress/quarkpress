package org.quarkpress.core;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The labels of a taxonomy as WordPress derives them: the given labels, names derived from the {@code name} and
 * {@code singular_name} labels (or the {@code label} argument), then WordPress's English defaults for a flat taxonomy
 * ("Tags") or a hierarchical one ("Categories"), and the template name built from the singular name. A default
 * WordPress leaves unset for one kind (the parent labels of a flat taxonomy, the tag-cloud labels of a hierarchical
 * one) is absent from the result.
 */
final class TaxonomyLabels {

    private static final String NAME_DESCRIPTION = "The name is how it appears on your site.";
    private static final String SLUG_DESCRIPTION = "The &#8220;slug&#8221; is the URL-friendly version of the name. It"
            + " is usually all lowercase and contains only letters, numbers, and hyphens.";
    private static final String PARENT_DESCRIPTION = "Assign a parent term to create a hierarchy. The term Jazz, for"
            + " example, would be the parent of Bebop and Big Band.";
    private static final String DESC_DESCRIPTION =
            "The description is not prominent by default; however, some themes may show it.";

    /** Key, flat default, hierarchical default, in WordPress's order; the menu name last. */
    private static final String[][] DEFAULTS = {
        {"name", "Tags", "Categories"},
        {"singular_name", "Tag", "Category"},
        {"search_items", "Search Tags", "Search Categories"},
        {"popular_items", "Popular Tags", null},
        {"all_items", "All Tags", "All Categories"},
        {"parent_item", null, "Parent Category"},
        {"parent_item_colon", null, "Parent Category:"},
        {"name_field_description", NAME_DESCRIPTION, NAME_DESCRIPTION},
        {"slug_field_description", SLUG_DESCRIPTION, SLUG_DESCRIPTION},
        {"parent_field_description", null, PARENT_DESCRIPTION},
        {"desc_field_description", DESC_DESCRIPTION, DESC_DESCRIPTION},
        {"edit_item", "Edit Tag", "Edit Category"},
        {"view_item", "View Tag", "View Category"},
        {"update_item", "Update Tag", "Update Category"},
        {"add_new_item", "Add Tag", "Add Category"},
        {"new_item_name", "New Tag Name", "New Category Name"},
        {"separate_items_with_commas", "Separate tags with commas", null},
        {"add_or_remove_items", "Add or remove tags", null},
        {"choose_from_most_used", "Choose from the most used tags", null},
        {"not_found", "No tags found.", "No categories found."},
        {"no_terms", "No tags", "No categories"},
        {"filter_by_item", null, "Filter by category"},
        {"items_list_navigation", "Tags list navigation", "Categories list navigation"},
        {"items_list", "Tags list", "Categories list"},
        {"most_used", "Most Used", "Most Used"},
        {"back_to_items", "&larr; Go to Tags", "&larr; Go to Categories"},
        {"item_link", "Tag Link", "Category Link"},
        {"item_link_description", "A link to a tag.", "A link to a category."},
        {"menu_name", "Tags", "Categories"}
    };

    private static final Set<String> DEFAULT_KEYS = defaultKeys();

    private TaxonomyLabels() {}

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
            if (!DEFAULT_KEYS.contains(entry.getKey()) && entry.getValue() != null) {
                labels.put(entry.getKey(), entry.getValue());
            }
        }
        if (!own.containsKey("template_name") && labels.containsKey("singular_name")) {
            labels.put("template_name", labels.get("singular_name") + " Archives");
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
