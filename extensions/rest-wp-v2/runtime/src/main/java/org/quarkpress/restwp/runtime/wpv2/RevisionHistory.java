package org.quarkpress.restwp.runtime.wpv2;

import java.util.function.Supplier;
import org.quarkpress.core.PostId;

/**
 * The revision count and newest revision of a post, and the revision a REST update saves, from a module that may be
 * absent (the revisions module): the post links and the post writer reach them through this type alone, so they load
 * and serve without that module.
 */
interface RevisionHistory {

    /** The number of a post's revisions and the id of the newest one, 0 when there is none. */
    record Count(long count, long latest) {}

    /**
     * The post's revisions as every reader's links count them: all of them, autosaves included, whoever may read
     * them; none (0) when revisions are off for the post.
     */
    Count of(PostId post);

    /**
     * Runs {@code work}, then saves the post's revision once, as a save would, unless the work threw: the revision
     * then holds what the work wrote after the post's row (its terms and meta), as WordPress's REST update saves its
     * revision last. Runs as the caller, who needs {@code edit_post} on the post.
     */
    <T> T revisingAfter(PostId post, Supplier<T> work);
}
