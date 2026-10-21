package org.quarkpress.taxonomy;

import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TermId;

/**
 * A term as stored: its taxonomy, name (as WordPress stores it, with {@code &}, {@code <} and {@code >} as entities),
 * slug, description, parent and the number of published posts in it. {@code version} changes with every write of the
 * term except a change of its count.
 */
public record TermSnapshot(
        TermId id,
        SiteId site,
        String taxonomy,
        String name,
        String slug,
        String description,
        Optional<TermId> parent,
        int count,
        int version) {

    public TermSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(taxonomy, "taxonomy");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(parent, "parent");
    }
}
