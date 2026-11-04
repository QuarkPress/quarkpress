package org.quarkpress.restwp.runtime;

import java.util.Optional;
import org.quarkpress.core.PostId;

/**
 * The latest autosave of a post, from a module that may be absent (the revisions module): the preview page reaches it
 * through this type alone, so it loads and serves without that module.
 */
interface AutosaveOverlay {

    /** The title and content an autosave holds. */
    record Autosave(String title, String content) {}

    /** The latest autosave of the post by any user; none when there is none. */
    Optional<Autosave> latest(PostId post);
}
