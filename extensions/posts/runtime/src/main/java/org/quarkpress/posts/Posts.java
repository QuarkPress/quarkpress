package org.quarkpress.posts;

import java.util.Optional;
import org.quarkpress.core.PostId;

/**
 * Posts of the current site, saved as WordPress's post API saves them and secured as its REST layer secures them.
 * Writes require the caller's capabilities (refused with Quarkus's {@code UnauthorizedException} when nobody is
 * authenticated, {@code ForbiddenException} otherwise) and refuse invalid input with {@link PostException}. Another
 * site is reached through {@code SiteScope}, whose work runs without the caller's identity.
 */
public interface Posts {

    /**
     * Creates a post (WordPress's {@code wp_insert_post}). Needs the type's {@code create_posts}; a status of
     * publish, future or private needs the type's {@code publish_posts}; an author other than the caller needs
     * {@code edit_others_posts}.
     */
    PostSnapshot create(PostChanges changes);

    /** The post with this id on the current site; a non-public post needs {@code read_post}. */
    Optional<PostSnapshot> find(PostId post);
}
