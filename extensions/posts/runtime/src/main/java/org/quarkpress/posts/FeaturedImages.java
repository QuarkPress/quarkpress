package org.quarkpress.posts;

import org.quarkpress.core.PostId;
import org.quarkpress.posts.runtime.JpaFeaturedImages;

/**
 * A post's featured image, the {@code _thumbnail_id} meta: an internal key that is never part of the post's
 * registered meta and is read and written only here. Only an attachment that {@link AttachmentThumbnails} says can
 * be a thumbnail is an image; without a module that knows attachment files, every attachment of the site is.
 */
public interface FeaturedImages {

    /** The featured image's id as an integer, as WordPress casts the stored value; 0 when the post has none. */
    static long of(PostSnapshot post) {
        return JpaFeaturedImages.thumbnailId(post);
    }

    /**
     * Sets the featured image as WordPress's REST write does, needing {@code edit_post}: 0 removes it; otherwise the
     * absolute value of {@code attachment} is looked up on the site: no post of that id leaves the post unchanged, an
     * attachment that can be a thumbnail becomes the featured image, and any other post (an attachment that cannot
     * included) removes the featured image. The post's modification date and version stay as they are. Answers
     * whether the stored value changed: setting the value already stored, or removing an absent one, answers false.
     */
    boolean set(PostId post, long attachment);
}
