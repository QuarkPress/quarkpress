package org.quarkpress.taxonomy;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteEvent;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TermId;

/**
 * A post's terms of one taxonomy were written (WordPress's {@code set_object_terms}): by an assignment, by a removal
 * that removed something, by a default term given on save, or by the delete of a term the post had. {@code before}
 * and {@code after} list the post's terms of the taxonomy by ascending id.
 */
public record TermsAssigned(
        SiteId site, Instant at, PostId post, String taxonomy, List<TermId> before, List<TermId> after)
        implements SiteEvent {

    public TermsAssigned {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(post, "post");
        Objects.requireNonNull(taxonomy, "taxonomy");
        before = List.copyOf(before);
        after = List.copyOf(after);
    }
}
