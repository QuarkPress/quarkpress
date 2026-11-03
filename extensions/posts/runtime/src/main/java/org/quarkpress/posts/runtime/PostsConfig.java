package org.quarkpress.posts.runtime;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;

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

    /**
     * How long an editor's post lock stays fresh after it was taken or refreshed (WordPress's
     * {@code wp_check_post_lock_window}): a lock whose time is not strictly later than now minus this window has
     * expired.
     */
    @WithDefault("150s")
    Duration lockWindow();

    /**
     * How long a preview session and a lock release token live, and the span of a preview token: a preview token
     * changes every half of this duration and stays valid for the half it was issued in and the one after, so a
     * preview link works for at least this long and at most twice as long, as WordPress's nonces do.
     */
    @WithDefault("12h")
    Duration previewLifetime();
}
