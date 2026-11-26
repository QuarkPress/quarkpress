package org.quarkword.taxonomy.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.quarkword.core.TaxonomyDefinition;
import org.quarkword.posts.TermQueryVariables;
import org.quarkword.taxonomy.Taxonomies;

/** The registered taxonomies' query variables, for the posts module's plain term links. */
@ApplicationScoped
public class RegisteredTermQueryVariables implements TermQueryVariables {

    @Inject
    Taxonomies taxonomies;

    @Override
    public Optional<String> of(String taxonomy) {
        return taxonomies.find(taxonomy).flatMap(TaxonomyDefinition::queryVar);
    }
}
