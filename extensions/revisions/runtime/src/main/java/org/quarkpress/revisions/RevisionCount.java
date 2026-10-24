package org.quarkpress.revisions;

import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.RevisionId;

/**
 * The newest revision of a post, autosaves included, and how many revisions it has (WordPress's {@code
 * wp_get_latest_revision_id_and_total_count}).
 */
public record RevisionCount(Optional<RevisionId> latest, long count) {

    public RevisionCount {
        Objects.requireNonNull(latest, "latest");
    }
}
