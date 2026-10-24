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
     * {@code edit_others_posts}. A post that is not created in the trash takes its slug, when not empty, from trashed
     * posts of the searchable types that hold it: they get the trashed suffix and keep their slug as the one to
     * restore. The type {@code revision} is refused ({@code invalid_post_type}): revisions are kept by the revisions
     * module.
     */
    PostSnapshot create(PostChanges changes);

    /**
     * Updates a post (WordPress's {@code wp_update_post}): the stored post merged with the changes, saved again by
     * every rule of a save, so every text field is filtered with the caller's settings. Needs {@code edit_post} on
     * the stored post; the status and author rules of {@link #create} apply too, with the capabilities of the type
     * the post will have, and changing the type needs the new type's {@code create_posts} and its publish and
     * edit-others rules for the status and author the post will have, given or stored. A draft, pending or
     * auto-draft post without a GMT date is dated now unless {@code editDate} is set; a local date given alone is
     * converted with the site's zone; dates not given are kept. The parent must be a post of the site (else
     * {@code invalid_parent}). On every update, for every type, the parent (given or stored) goes through WordPress's
     * loop check: a post that is its own parent, or whose parent's chain leads back to it, is left without parent; a
     * loop further up the parent's chain is cut by updating each of its posts, in WordPress's order, to have no
     * parent. Unlike WordPress, which skips a loop post whose update fails and keeps the loop, such a failure makes
     * the whole update fail. A post leaving the trash gets back the slug it had before trashing, which wins over a
     * given one; a post entering it gets the trashed suffix; a post that stays out of it takes its slug from trashed
     * posts as {@link #create} does. With {@code expectedVersion}, a post that changed since that version is refused
     * with {@code jakarta.persistence.OptimisticLockException}, before any write and without marking the caller's
     * transaction for rollback, so the caller can read the post again and retry. A failed update changes nothing:
     * once it has written, the failure also marks the caller's transaction for rollback. Changing the type to
     * {@code revision} is refused ({@code invalid_post_type}).
     */
    PostSnapshot update(PostId post, PostChanges changes);

    /**
     * Publishes a post as WordPress's scheduled publishing does ({@code wp_publish_post}): only the status changes (no
     * date, slug or modification date), and the save is announced as a status change and a save, for attachments
     * too, but not as an update. Needs {@code publish_post}. False when it was published already.
     */
    boolean publish(PostId post);

    /**
     * Moves a post to the trash (WordPress's {@code wp_trash_post}); needs {@code delete_post}. Any type can be trashed
     * this way. A page named by the front page or posts page setting is no longer named there, the post is unstuck,
     * the status it had and the time are recorded in its meta ({@code _wp_trash_meta_status}, {@code
     * _wp_trash_meta_time}), and it is saved with status trash by every rule of an update: its slug gets the trashed
     * suffix (the slug it had is kept in {@code _wp_desired_post_slug}) and a draft whose date floats is dated now.
     * Its children keep it as their parent. Returns the post as it was before; empty when it was in the trash already,
     * or when the save was refused before writing anything, in which case nothing changed and the caller's
     * transaction stays usable (WordPress keeps the recorded meta then). A failed call changes nothing: a failure
     * after the first write marks the caller's transaction for rollback and propagates. A missing post, or one of
     * another site, is {@code invalid_post}. With the trash turned off ({@code quarkpress.posts.empty-trash-days=0})
     * the post is deleted for good instead, as {@link #delete} with {@code force} does, even when it is in the trash
     * already.
     */
    Optional<PostSnapshot> trash(PostId post);

    /**
     * Deletes a post (WordPress's {@code wp_delete_post}); needs {@code delete_post}. Without {@code force}, a post or
     * page that is not in the trash is trashed instead, as {@link #trash} does, unless the trash is turned off; every
     * other type, and a post or page in the trash, is deleted at once.
     *
     * <p>A delete announces itself with {@code PostDeleting} before anything changes, inside the transaction, so
     * observers can clean up what they keep about the post. Then same-type children of a hierarchical type get its
     * parent, as do attachments (except for a deleted attachment, which, as in WordPress, hands nothing down); every
     * other child is left without parent, where WordPress would leave it naming the missing post. Only the children's
     * parent changes, not their version or modification date. The row goes with its meta. In WordPress's order, a page
     * named by the front page or posts page setting is no longer named there, a sticky post is announced as unstuck,
     * and a page named by the privacy policy page setting is no longer named there; then {@code PostDeleted} is fired.
     * Comments are not deleted yet.
     *
     * <p>Returns the post as it was before (for a post trashed instead, what {@link #trash} returns). A missing post,
     * or one of another site, is {@code invalid_post}. A failed delete changes nothing: a failure after the first
     * write marks the caller's transaction for rollback and propagates.
     */
    Optional<PostSnapshot> delete(PostId post, boolean force);

    /**
     * Restores a trashed post (WordPress's {@code wp_untrash_post}) as a draft, an attachment as inherit; needs
     * {@code delete_post}, as wp-admin checks it. The trash records are removed and the post is saved by every rule
     * of an update, which gives back the slug it had before trashing (unless that was empty) and keeps its date; it
     * stays unstuck. Returns the post as it was before; empty when it was not in the trash, or when the save was
     * refused before writing anything, in which case nothing changed. A failed call changes nothing: a failure after
     * the first write marks the caller's transaction for rollback and propagates. A missing post, or one of another
     * site, is {@code invalid_post}.
     */
    Optional<PostSnapshot> untrash(PostId post);

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

    /**
     * Sticks a post to the front of the site (WordPress's {@code stick_post}, as its REST layer allows it): only a post
     * of type {@code post}, and not one with a password (else {@code rest_invalid_field}); needs {@code edit_post} on
     * the post and the {@code post} type's {@code edit_others_posts} and {@code publish_posts}. False when it was
     * sticky already; a change is announced with {@code StickyChanged}.
     */
    boolean stick(PostId post);

    /** Unsticks a post; same rules as {@link #stick} except the password. False when it was not sticky. */
    boolean unstick(PostId post);

    /**
     * The ids of the site's sticky posts, by id (WordPress's {@code sticky_posts} option). Like that option the list is
     * public: it is not filtered by what the caller may read.
     */
    List<PostId> stickyPosts();

    /**
     * Posts of the current site matching {@code query} (WordPress's {@code WP_Query}), after the {@link
     * PostQueryFilter}s changed it. Asking for statuses other than publish needs the queried types' {@code
     * edit_posts}; what each caller then sees is described on {@link PostQuery}.
     */
    PostPage query(PostQuery query);
}
