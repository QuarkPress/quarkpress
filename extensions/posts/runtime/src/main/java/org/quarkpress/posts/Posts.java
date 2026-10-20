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

    /**
     * Updates a post (WordPress's {@code wp_update_post}): the stored post merged with the changes, saved again by
     * every rule of a save, so every text field is filtered with the caller's settings. Needs {@code edit_post} on
     * the stored post; the status and author rules of {@link #create} apply too, with the capabilities of the type
     * the post will have, and changing the type needs the new type's {@code create_posts}. A draft, pending or
     * auto-draft post without a GMT date is dated now unless {@code editDate} is set; a local date given alone is
     * converted with the site's zone; dates not given are kept. With {@code expectedVersion}, a post that changed
     * since that version is refused with {@code jakarta.persistence.OptimisticLockException}, before any write and
     * without marking the caller's transaction for rollback, so the caller can read the post again and retry. A
     * failed update changes nothing: once it has written, the failure also marks the caller's transaction for
     * rollback.
     */
    PostSnapshot update(PostId post, PostChanges changes);

    /**
     * Publishes a post as WordPress's scheduled publishing does ({@code wp_publish_post}): only the status changes (no
     * date, slug or modification date), and the save is announced as a status change and a save, for attachments
     * too, but not as an update. Needs {@code publish_post}. False when it was published already.
     */
    boolean publish(PostId post);

    /** The post with this id on the current site; a non-public post needs {@code read_post}. */
    Optional<PostSnapshot> find(PostId post);
}
