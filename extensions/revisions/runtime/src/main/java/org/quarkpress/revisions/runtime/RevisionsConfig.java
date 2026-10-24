package org.quarkpress.revisions.runtime;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/** Revisions settings read when the application starts. */
@ConfigMapping(prefix = "quarkpress.revisions")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface RevisionsConfig {

    /**
     * How many revisions of a post are kept (WordPress's {@code WP_POST_REVISIONS}): a negative number keeps every
     * revision, 0 saves none, a positive number keeps that many (the oldest are removed when a new one is saved).
     * The starting point of every post's limit; a {@code RevisionsToKeepFilter} and the per-site feature
     * {@code revisions} decide after it.
     */
    @WithDefault("-1")
    int keep();
}
