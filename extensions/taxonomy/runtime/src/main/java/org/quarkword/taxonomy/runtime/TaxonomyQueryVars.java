package org.quarkword.taxonomy.runtime;

import java.util.Optional;
import org.quarkword.core.TaxonomyDefinition;
import org.quarkword.posts.runtime.Slugs;

/**
 * WordPress's normalisation of a taxonomy's {@code query_var} at registration: a given name is slugified as
 * {@code sanitize_title_with_dashes} does, and one that leaves nothing is refused.
 */
public final class TaxonomyQueryVars {

    private TaxonomyQueryVars() {}

    /** {@code taxonomy} with its query variable slugified; the same instance when there is nothing to change. */
    public static TaxonomyDefinition slugified(TaxonomyDefinition taxonomy) {
        Optional<String> given = taxonomy.queryVar();
        if (given.isEmpty()) {
            return taxonomy;
        }
        String slug = Slugs.sanitizeTitleForQuery(given.get());
        if (slug.isEmpty()) {
            throw new IllegalArgumentException(
                    "taxonomy '" + taxonomy.key() + "': the query variable '" + given.get() + "' leaves no slug");
        }
        return slug.equals(given.get()) ? taxonomy : taxonomy.withQueryVar(slug);
    }
}
