package org.quarkpress.security;

import java.util.Map;
import java.util.Objects;

/**
 * The four capability names of a taxonomy: managing, editing, deleting and assigning its terms. A taxonomy registered
 * without them uses {@code manage_categories} for the first three and {@code edit_posts} for assigning.
 */
public record TaxonomyCapabilities(
        String name, String manageTerms, String editTerms, String deleteTerms, String assignTerms) {

    public static final TaxonomyCapabilities CATEGORY = new TaxonomyCapabilities(
            "category", "manage_categories", "edit_categories", "delete_categories", "assign_categories");

    public static final TaxonomyCapabilities POST_TAG = new TaxonomyCapabilities(
            "post_tag", "manage_post_tags", "edit_post_tags", "delete_post_tags", "assign_post_tags");

    public TaxonomyCapabilities {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(manageTerms, "manageTerms");
        Objects.requireNonNull(editTerms, "editTerms");
        Objects.requireNonNull(deleteTerms, "deleteTerms");
        Objects.requireNonNull(assignTerms, "assignTerms");
    }

    public static TaxonomyCapabilities defaults(String name) {
        return of(name, Map.of());
    }

    /** The defaults with entries of {@code overrides} (keys manage_terms, edit_terms, delete_terms, assign_terms). */
    public static TaxonomyCapabilities of(String name, Map<String, String> overrides) {
        return new TaxonomyCapabilities(
                name,
                overrides.getOrDefault("manage_terms", "manage_categories"),
                overrides.getOrDefault("edit_terms", "manage_categories"),
                overrides.getOrDefault("delete_terms", "manage_categories"),
                overrides.getOrDefault("assign_terms", "edit_posts"));
    }
}
