package org.quarkword.posts;

import java.time.Instant;
import java.util.Optional;
import org.quarkword.core.PostId;

/**
 * The latest autosave of a post, from a module that may be absent (the revisions module): the preview page and the
 * editor's settings reach it through this type alone, so they load and serve without that module.
 */
public interface AutosaveOverlay {

    /** The title and content an autosave holds. */
    record Autosave(String title, String content) {}

    /** When an autosave was last written, and its id. */
    record Stamp(long id, Instant writtenAt) {}

    /** The latest autosave of the post by any user; none when there is none. */
    Optional<Autosave> latest(PostId post);

    /** The newest autosave of the post by any user, however old; none when there is none. */
    Optional<Stamp> latestStamp(PostId post);
}
