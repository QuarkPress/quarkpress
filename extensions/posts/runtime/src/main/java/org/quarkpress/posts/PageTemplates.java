package org.quarkpress.posts;

import org.quarkpress.core.PostId;
import org.quarkpress.posts.runtime.JpaPageTemplates;

/**
 * A post's page template, the {@code _wp_page_template} meta: an internal key that is never part of the post's
 * registered meta. Which templates a theme offers is the caller's concern; the value is stored as given.
 */
public interface PageTemplates {

    /** The meta key. */
    String KEY = "_wp_page_template";

    /** The stored template name; {@code ""} when the post has none. */
    static String of(PostSnapshot post) {
        return JpaPageTemplates.template(post);
    }

    /**
     * Stores {@code template} as given, {@code ""} included, needing {@code edit_post}. The post's modification date
     * and version stay as they are.
     */
    void set(PostId post, String template);
}
