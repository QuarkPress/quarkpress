package org.quarkpress.testing.wpscenario;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.revisions.RevisionsToKeepFilter;

/**
 * Applies a scenario's {@code revisions_keep} on QuarkPress's side, as the driver defines {@code WP_POST_REVISIONS}
 * for WordPress's process: with an override set, every post whose limit is not already 0 gets it. The test
 * application's configured limit is -1 (never 0), so a limit of 0 here means a type without revisions support, which
 * WordPress's constant leaves at 0 too.
 */
@ApplicationScoped
public class TierBRevisionsKeep implements RevisionsToKeepFilter {

    static final AtomicReference<OptionalInt> OVERRIDE = new AtomicReference<>(OptionalInt.empty());

    @Override
    public int apply(int keep, PostSnapshot post) {
        OptionalInt override = OVERRIDE.get();
        return override.isPresent() && keep != 0 ? override.getAsInt() : keep;
    }
}
