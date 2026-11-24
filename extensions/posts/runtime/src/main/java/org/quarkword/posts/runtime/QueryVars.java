package org.quarkword.posts.runtime;

import java.util.Optional;
import org.quarkword.core.PostTypeDefinition;

/**
 * WordPress's normalisation of a post type's {@code query_var} at registration: a given name is slugified as
 * {@code sanitize_title_with_dashes} does, and one that leaves nothing is refused.
 */
public final class QueryVars {

    private QueryVars() {}

    /** {@code type} with its query variable slugified; the same instance when there is nothing to change. */
    public static PostTypeDefinition slugified(PostTypeDefinition type) {
        Optional<String> given = type.queryVar();
        if (given.isEmpty()) {
            return type;
        }
        String slug = Slugs.sanitizeTitleForQuery(given.get());
        if (slug.isEmpty()) {
            throw new IllegalArgumentException(
                    "post type '" + type.key() + "': the query variable '" + given.get() + "' leaves no slug");
        }
        return slug.equals(given.get()) ? type : type.withQueryVar(slug);
    }
}
