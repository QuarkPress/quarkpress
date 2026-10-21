package org.quarkpress.taxonomy.runtime;

import java.util.List;
import java.util.Map;
import org.quarkpress.core.TaxonomyDefinition;

/**
 * WordPress's built-in taxonomies that QuarkPress registers, with the arguments WordPress registers them with. The
 * other built-ins (menus, link categories, post formats, block-theme taxonomies) come with the modules that use them.
 */
public final class BuiltInTaxonomies {

    private BuiltInTaxonomies() {}

    public static List<TaxonomyDefinition> all() {
        return List.of(category(), tag());
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
}
