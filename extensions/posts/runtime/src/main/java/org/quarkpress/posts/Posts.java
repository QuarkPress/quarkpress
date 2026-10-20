package org.quarkpress.posts;

import java.util.List;
import java.util.Optional;
import org.quarkpress.core.PostId;

/**
 * Posts of the current site, saved as WordPress's post API saves them and secured as its REST layer secures them.
 * Writes require the caller's capabilities (refused with Quarkus's {@code UnauthorizedException} when nobody is
 * authenticated, {@code ForbiddenException} otherwise) and refuse invalid input with {@link PostException}. A
 * capability refusal comes before any write and leaves the caller's transaction usable. Another site is reached
 * through {@code SiteScope}, whose work runs without the caller's identity.
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
     * converted with the site's zone; dates not given are kept. The parent must be a post of the site (else
     * {@code invalid_parent}). On every update, for every type, the parent (given or stored) goes through WordPress's
     * loop check: a post that is its own parent, or whose parent's chain leads back to it, is left without parent; a
     * loop further up the parent's chain is cut by updating each of its posts, in WordPress's order, to have no
     * parent. Unlike WordPress, which skips a loop post whose update fails and keeps the loop, such a failure makes
     * the whole update fail. With {@code expectedVersion}, a post that changed since that version is refused with
     * {@code jakarta.persistence.OptimisticLockException}, before any write and without marking the caller's
     * transaction for rollback, so the caller can read the post again and retry. A failed update changes nothing:
     * once it has written, the failure also marks the caller's transaction for rollback.
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

    /**
     * The post's ancestors, nearest first (WordPress's {@code get_post_ancestors}): the walk up the parents stops at
     * a post without parent, at a missing post, at the post itself or at a post already listed. A missing post, or one
     * of another site, is {@code invalid_post}; a non-public post needs {@code read_post}.
     */
    List<PostId> ancestors(PostId post);

    /**
     * The post's slug path (WordPress's {@code get_page_uri}): the slugs of its ancestors, farthest first, then its own
     * slug, joined by slashes; ancestors whose slug is empty or "0" are skipped. Read rules as for
     * {@link #ancestors}.
     */
    String uri(PostId post);
}
