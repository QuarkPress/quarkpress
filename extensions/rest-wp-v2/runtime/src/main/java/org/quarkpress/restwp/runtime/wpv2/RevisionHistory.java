package org.quarkpress.restwp.runtime.wpv2;

import org.quarkpress.core.PostId;

/**
 * The revision count and newest revision of a post, from a module that may be absent (the revisions module): the
 * post links reach them through this type alone, so they load and serve without that module.
 */
interface RevisionHistory {

    /** The number of a post's revisions and the id of the newest one, 0 when there is none. */
    record Count(long count, long latest) {}

    /**
     * The post's revisions as every reader's links count them: all of them, autosaves included, whoever may read
     * them; none (0) when revisions are off for the post.
     */
    Count of(PostId post);
}
