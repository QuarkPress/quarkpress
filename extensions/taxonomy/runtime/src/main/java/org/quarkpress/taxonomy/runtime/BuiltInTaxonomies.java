package org.quarkpress.taxonomy.runtime;

import java.util.List;
import java.util.Map;
import org.quarkpress.core.TaxonomyDefinition;

/**
 * WordPress's built-in taxonomies that QuarkPress registers, with the arguments WordPress registers them with. The
 * other built-ins (menus, link categories, block-theme taxonomies) come with the modules that use them.
 */
public final class BuiltInTaxonomies {

    private BuiltInTaxonomies() {}

    public static List<TaxonomyDefinition> all() {
        return List.of(category(), tag(), postFormat());
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
}
