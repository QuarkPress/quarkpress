package org.quarkpress.taxonomy;

import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.TermId;

/** A term as it is about to be written: sanitized, its slug made unique. */
public record TermDraft(String name, String slug, String description, Optional<TermId> parent) {

    public TermDraft {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(parent, "parent");
    }
}
