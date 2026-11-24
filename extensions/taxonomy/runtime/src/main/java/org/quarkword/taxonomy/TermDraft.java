package org.quarkword.taxonomy;

import java.util.Objects;
import java.util.Optional;
import org.quarkword.core.TermId;

/** A term as it is about to be written: sanitized, its slug made unique. */
public record TermDraft(String name, String slug, String description, Optional<TermId> parent) {

    public TermDraft {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(parent, "parent");
    }
}
