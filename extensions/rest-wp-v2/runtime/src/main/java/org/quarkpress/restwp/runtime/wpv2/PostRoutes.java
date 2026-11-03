package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.quarkpress.core.PostId;
import org.quarkpress.core.PostStatusDefinition;
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

    /**
     * The routes of a post type shown in REST, as every controller serving or naming its posts derives them: the
     * type's REST namespace and base, the taxonomies shown in REST that are attached to it (registration order) and
     * its item schema, whose statuses are {@code creatable}.
     *
     * @param taxonomies every registered taxonomy, in registration order
     * @param creatable the statuses a post may be written with, from {@link #creatable(List)}
     */
    static PostRoutes of(
            PostTypeDefinition type, List<TaxonomyDefinition> taxonomies, MetaFields meta, List<String> creatable) {
        List<TaxonomyDefinition> restTaxonomies = new ArrayList<>();
        for (TaxonomyDefinition taxonomy : taxonomies) {
            if (taxonomy.showInRest() && taxonomy.attachedTo(type.key())) {
                restTaxonomies.add(taxonomy);
            }
        }
        ObjectNode schema = PostSchema.of(type, restTaxonomies, meta.schema(type.key()), creatable);
        return new PostRoutes(type, TypeExtras.of(type.key()).restNamespace(), base(type), schema, restTaxonomies);
    }

    /** The names of the statuses that are not internal, in registration order. */
    static List<String> creatable(List<PostStatusDefinition> statuses) {
        List<String> creatable = new ArrayList<>();
        for (PostStatusDefinition status : statuses) {
            if (!status.internal()) {
                creatable.add(status.name());
            }
        }
        return creatable;
    }

    /** The REST base of a post type: its REST base, or its key. */
    static String base(PostTypeDefinition type) {
        return type.restBase().filter(rest -> !rest.isEmpty()).orElse(type.key());
    }

    /** The REST base of a taxonomy of the type: its REST base, or its key. */
    static String base(TaxonomyDefinition taxonomy) {
        return taxonomy.restBase().filter(rest -> !rest.isEmpty()).orElse(taxonomy.key());
    }
}
