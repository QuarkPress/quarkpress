package org.quarkpress.posts;

import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;

/**
 * Whether an attachment can be a featured image: an image of size {@code thumbnail} can be made for it. Without a
 * module that knows attachment files every attachment can; the media module answers from the file it stores.
 */
public interface AttachmentThumbnails {

    /** Whether an image of size {@code thumbnail} can be made for {@code attachment} of {@code site}. */
    boolean canBeThumbnail(SiteId site, PostId attachment);
}
