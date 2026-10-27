package org.quarkpress.revisions;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.quarkpress.core.PostId;
import org.quarkpress.core.RevisionId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostSnapshot;

/**
 * Revisions and autosaves of the current site's posts, saved as WordPress's revision API saves them and secured as
 * its REST layer secures them. A revision is written after every update-like save of a post (an update, a trash, an
 * untrash, a publish) by an observer inside that save, when the post's revisioned fields or meta changed since its
 * latest regular revision; this service saves one on demand, keeps autosaves, restores and deletes revisions, and
 * reads them. Writes require the caller's capabilities (refused with Quarkus's {@code UnauthorizedException} when
 * nobody is authenticated, {@code ForbiddenException} otherwise) and refuse invalid input with
 * {@link RevisionException} or, for a missing post, the posts module's {@code PostException}. A capability refusal,
 * and an input refusal raised before anything is written, leave the caller's transaction usable; once a call has
 * written, any failure marks the transaction for rollback, so a failed call changes nothing. Another site is reached
 * through {@code SiteScope}, whose work runs without the caller's identity. Every write takes the site's post lock,
 * so revision and post writes of one site apply one after the other.
 */
public interface Revisions {

    /**
     * Saves a revision of the post now (WordPress's {@code wp_save_post_revision}), as the save of an update would:
     * nothing for an auto-draft, a type without revisions support, a post whose limit is 0, or a post unchanged (title,
     * content, excerpt after whitespace normalization, and revisioned meta) since its latest regular revision; else a
     * copy of the post's current revisioned fields, filtered for the caller as a save filters them, with the caller as
     * author and the post's modification time as its time, announced with {@link RevisionCreated}; then the oldest
     * revisions beyond the post's limit are removed (autosaves count but are never removed this way), each announced
     * with {@link RevisionDeleted}. Needs {@code edit_post} on the post. A missing post, or one of another site, is
     * {@code invalid_post}.
     */
    Optional<RevisionSnapshot> save(PostId post);

    /** The revision with this id on the current site, autosaves included; needs {@code edit_post} on its post. */
    Optional<RevisionSnapshot> find(RevisionId revision);

    /**
     * The post's revisions as {@code query} asks for them (WordPress's {@code wp_get_post_revisions}); nothing for a
     * post whose limit is 0 unless the query turns that check off. Needs {@code edit_post} on the post; a missing
     * post, or one of another site, is {@code invalid_post}.
     */
    List<RevisionSnapshot> ofPost(PostId post, RevisionQuery query);

    /**
     * The post's autosave by {@code user}, or without a user the latest autosave of any user (the newest by time,
     * then by id); an autosave older than the post's last change is still the answer (WordPress's
     * {@code wp_get_post_autosave}). Needs {@code edit_post} on the post.
     */
    Optional<RevisionSnapshot> autosaveOf(PostId post, Optional<UserId> user);

    /** Every user's autosaves of the post, newest first, whatever the post's limit. Needs {@code edit_post}. */
    List<RevisionSnapshot> autosavesOf(PostId post);

    /**
     * The newest revision (an autosave counts) and the number of all revisions of the post; {@code
     * revisions_not_enabled} when the post's limit is 0. Needs {@code edit_post}.
     */
    RevisionCount latestAndCount(PostId post);

    /**
     * Autosaves the post for the caller (WordPress's REST autosaves endpoint). The post's type must be autosaved
     * ({@code autosave_not_supported}); the caller needs {@code edit_post} on the post; meta keys that are not
     * revisioned for the type are refused with {@code IllegalArgumentException}. A draft or auto-draft of the caller's
     * own is updated with the given fields through the full save path, creates no revision, ignores the given meta,
     * and is the result. Otherwise the caller's autosave is updated (re-dated now; fields not given keep their
     * values; given meta keys replace theirs) when a given field, after whitespace normalization, or any revisioned
     * meta key, when meta is given, differs from the post; returned untouched when nothing differs; or created without
     * comparison when the caller has none (fields not given stored empty, the post's revisioned meta copied, then the
     * given keys). The limit and the revisions feature play no part. A missing post, or one of another site, is
     * {@code invalid_post}. The system's autosave has no author; the system writes to the newest authorless autosave of
     * the post.
     */
    AutosaveResult autosave(PostId post, AutosaveChanges changes);

    /**
     * Restores a revision or autosave onto its post (WordPress's {@code wp_restore_post_revision} as the revisions
     * screen allows it): needs {@code edit_post} on the post, and the post's revisions enabled unless the revision is
     * an autosave ({@code revisions_not_enabled}). The post is updated as the caller with the revision's fields
     * (every revisioned field of the post, which is what an empty set of fields means, or the given subset;
     * WordPress takes that full set from the revision instead): the ordinary update, which filters the text for the
     * caller, dates a floating draft now, fires the post's events and saves a revision of the result unless it equals
     * the latest regular revision; then {@link RevisionRestored} fires; then the revision's revisioned meta replaces
     * the post's (a key the revision lacks ends empty), so the revision the restore just saved still carries the
     * post's previous meta. Returns the post afterwards. A missing revision, or one of another site, is
     * {@code rest_post_invalid_id}. A restore refused by the update's own input check (for example
     * {@code empty_content}) leaves the caller's transaction usable; the posts module marks its own failures after its
     * first write.
     */
    PostSnapshot restore(RevisionId revision, Set<RevisionField> fields);

    /**
     * Deletes a revision or autosave (WordPress's {@code wp_delete_post_revision}), announced with
     * {@link RevisionDeleted}; the post's own records are left alone. Only the system identity may: deleting a
     * revision is refused for every user, as WordPress refuses it. Empty for a missing revision, or one of another
     * site, when the caller is the system.
     */
    Optional<RevisionSnapshot> delete(RevisionId revision);

    /**
     * The number of revisions of the current site's posts, autosaves included. Needs {@code edit_others_posts} on the
     * site.
     */
    long countAll();
}
