package org.quarkpress.security;

import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.TermId;

/** What a term capability check needs: the term's id and its taxonomy's capability names (empty when unregistered). */
public record TermFacts(TermId id, Optional<TaxonomyCapabilities> taxonomy) implements CapObject {

    public TermFacts {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(taxonomy, "taxonomy");
    }

    public static TermFacts of(TermId id, TaxonomyCapabilities taxonomy) {
        return new TermFacts(id, Optional.of(taxonomy));
    }
}
