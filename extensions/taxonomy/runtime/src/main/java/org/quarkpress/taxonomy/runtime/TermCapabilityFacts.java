package org.quarkpress.taxonomy.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.quarkpress.security.TermFacts;
import org.quarkpress.taxonomy.Taxonomies;
import org.quarkpress.taxonomy.TermSnapshot;

/** What security's capability checks need to know about a term: its id and its taxonomy's capability names. */
@ApplicationScoped
public class TermCapabilityFacts {

    @Inject
    Taxonomies taxonomies;

    /** The facts of a stored term; a term of a taxonomy that is not registered has no capability names. */
    public TermFacts of(TermSnapshot term) {
        return new TermFacts(term.id(), taxonomies.capabilities(term.taxonomy()));
    }
}
