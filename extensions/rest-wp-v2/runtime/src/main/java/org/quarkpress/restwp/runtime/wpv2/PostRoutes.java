package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.quarkpress.core.PostId;
import org.quarkpress.core.PostTypeDefinition;
import org.quarkpress.core.TaxonomyDefinition;

/**
 * One REST post type's routes: its definition, namespace, base, item schema (held once: selections are kept per
 * schema) and the taxonomies of the type shown in REST, in registration order.
 */
record PostRoutes(
        PostTypeDefinition type,
        String namespace,
        String base,
        ObjectNode schema,
        List<TaxonomyDefinition> taxonomies) {

    PostRoutes {
        taxonomies = List.copyOf(taxonomies);
    }

    String key() {
        return type.key();
    }

    /** {@code /<namespace>/<base>}. */
    String collection() {
        return "/" + namespace + "/" + base;
    }

    String item(PostId id) {
        return collection() + "/" + id.value();
    }

    /** Whether the type's fields include the feature's (WordPress's fixed lists for posts and pages). */
    boolean supports(String feature) {
        return PostSchema.supports(type).contains(feature);
    }

    /** Whether the item schema has the property. */
    boolean has(String property) {
        return schema.get("properties").has(property);
    }

    /** The REST base of a taxonomy of the type: its REST base, or its key. */
    static String base(TaxonomyDefinition taxonomy) {
        return taxonomy.restBase().filter(rest -> !rest.isEmpty()).orElse(taxonomy.key());
    }
}
