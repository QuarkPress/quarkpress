package org.quarkpress.posts.runtime;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/** Posts settings read when the application starts. */
@ConfigMapping(prefix = "quarkpress.posts")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface PostsConfig {

    /**
     * Days a trashed post or page is kept before it may be purged (WordPress's {@code EMPTY_TRASH_DAYS}). 0 turns
     * the trash off: trashing deletes at once, and deleting a post or page no longer trashes it first.
     */
    @WithDefault("30")
    int emptyTrashDays();
}
