package org.quarkpress.taxonomy;

import java.util.List;
import java.util.Optional;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.security.TaxonomyCapabilities;

/**
 * The registered taxonomies: WordPress's built-in {@code category} and {@code post_tag}, then the taxonomies
 * extensions and the application register, in key order. Fixed when the application is built.
 */
public interface Taxonomies {

    List<TaxonomyDefinition> all();

    Optional<TaxonomyDefinition> find(String key);

    boolean registered(String key);

    /** Whether the taxonomy is registered and hierarchical. */
    boolean hierarchical(String key);

    /** The taxonomies attached to posts of {@code postType}, in registration order (get_object_taxonomies). */
    List<TaxonomyDefinition> forType(String postType);

    /** The taxonomy's capability names; empty for a taxonomy that is not registered. */
    Optional<TaxonomyCapabilities> capabilities(String key);

    /** The registered taxonomy; refused with {@code TermException} {@code invalid_taxonomy} when there is none. */
    TaxonomyDefinition require(String key);
}
