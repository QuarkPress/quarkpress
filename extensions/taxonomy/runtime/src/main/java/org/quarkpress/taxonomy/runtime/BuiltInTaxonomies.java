package org.quarkpress.taxonomy.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.quarkpress.core.TaxonomyDefinition;

/**
 * WordPress's built-in taxonomies that QuarkPress registers, with the arguments WordPress registers them with, in its
 * order (before every taxonomy an application registers). The other built-ins (menus, link categories, block-theme
 * taxonomies) come with the modules that use them.
 */
public final class BuiltInTaxonomies {

    private BuiltInTaxonomies() {}

    public static List<TaxonomyDefinition> all() {
        return List.of(category(), tag(), postFormat(), patternCategory());
    }

    private static TaxonomyDefinition category() {
        return TaxonomyDefinition.builder("category", List.of("post"))
                .hierarchical(true)
                .publicly(true)
                .showUi(true)
                .showAdminColumn(true)
                .capabilities(Map.of(
                        "manage_terms", "manage_categories",
                        "edit_terms", "edit_categories",
                        "delete_terms", "delete_categories",
                        "assign_terms", "assign_categories"))
                .showInRest(true)
                .restBase("categories")
                .builtIn()
                .build();
    }

    private static TaxonomyDefinition tag() {
        return TaxonomyDefinition.builder("post_tag", List.of("post"))
                .hierarchical(false)
                .publicly(true)
                .showUi(true)
                .showAdminColumn(true)
                .capabilities(Map.of(
                        "manage_terms", "manage_post_tags",
                        "edit_terms", "edit_post_tags",
                        "delete_terms", "delete_post_tags",
                        "assign_terms", "assign_post_tags"))
                .showInRest(true)
                .restBase("tags")
                .builtIn()
                .build();
    }

    /**
     * Post formats, as WordPress registers them: public and queryable, flat, without admin screens (no interface
     * flag but {@code show_in_nav_menus}), managed with {@code manage_categories} and assigned with {@code edit_posts},
     * outside REST, labelled {@code Formats}/{@code Format} with the generic tag labels for the rest. WordPress also
     * gives it the query variable {@code post_format} and the rewrite slug {@code type}, which the definition does not
     * keep.
     */
    private static TaxonomyDefinition postFormat() {
        return TaxonomyDefinition.builder("post_format", List.of("post"))
                .label("Formats")
                .labels(Map.of("name", "Formats", "singular_name", "Format"))
                .hierarchical(false)
                .publicly(true)
                .publiclyQueryable(true)
                .showUi(false)
                .showInMenu(false)
                .showInNavMenus(true)
                .showTagcloud(false)
                .showInQuickEdit(false)
                .showAdminColumn(false)
                .capabilities(Map.of(
                        "manage_terms", "manage_categories",
                        "edit_terms", "manage_categories",
                        "delete_terms", "manage_categories",
                        "assign_terms", "edit_posts"))
                .showInRest(false)
                .builtIn()
                .build();
    }

    /**
     * The categories of synced patterns ({@code wp_block} only), as WordPress registers them: not public nor
     * publicly queryable, flat, with admin screens and an admin column but not in navigation menus nor a tag cloud,
     * shown in REST under its key, with the default capabilities ({@code manage_categories} to manage, edit and
     * delete terms, {@code edit_posts} to assign them), no default term, and WordPress's pattern category labels
     * (the flat defaults for the rest). WordPress also turns off its query variable and rewrite rules, which the
     * definition does not keep.
     */
    private static TaxonomyDefinition patternCategory() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("name", "Pattern Categories");
        labels.put("singular_name", "Pattern Category");
        labels.put("add_new_item", "Add Category");
        labels.put("add_or_remove_items", "Add or remove pattern categories");
        labels.put("back_to_items", "&larr; Go to Pattern Categories");
        labels.put("choose_from_most_used", "Choose from the most used pattern categories");
        labels.put("edit_item", "Edit Pattern Category");
        labels.put("item_link", "Pattern Category Link");
        labels.put("item_link_description", "A link to a pattern category.");
        labels.put("items_list", "Pattern Categories list");
        labels.put("items_list_navigation", "Pattern Categories list navigation");
        labels.put("new_item_name", "New Pattern Category Name");
        labels.put("no_terms", "No pattern categories");
        labels.put("not_found", "No pattern categories found.");
        labels.put("popular_items", "Popular Pattern Categories");
        labels.put("search_items", "Search Pattern Categories");
        labels.put("separate_items_with_commas", "Separate pattern categories with commas");
        labels.put("update_item", "Update Pattern Category");
        labels.put("view_item", "View Pattern Category");
        return TaxonomyDefinition.builder("wp_pattern_category", List.of("wp_block"))
                .labels(labels)
                .hierarchical(false)
                .publicly(false)
                .publiclyQueryable(false)
                .showUi(true)
                .showInNavMenus(false)
                .showInRest(true)
                .showAdminColumn(true)
                .showTagcloud(false)
                .builtIn()
                .build();
    }
}
