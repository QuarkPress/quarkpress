package org.quarkpress.taxonomy;

import org.quarkpress.core.TermId;

/**
 * Terms of the current site, saved as WordPress's taxonomy API saves them and secured as its REST layer secures them.
 * Writes require the caller's capabilities (refused with Quarkus's {@code UnauthorizedException} when nobody is
 * authenticated, {@code ForbiddenException} otherwise) and refuse invalid input with {@link TermException}. A
 * capability refusal, and an input refusal raised before anything is written, leave the caller's transaction usable;
 * once a call has written, any failure marks the transaction for rollback, so a failed call changes nothing. Another
 * site is reached through {@code SiteScope}, whose work runs without the caller's identity. Every write takes the
 * site's post lock, so term and post writes of one site apply one after the other.
 */
public interface Terms {

    /**
     * Creates a term (WordPress's {@code wp_insert_term}). Needs the taxonomy's {@code edit_terms} for a hierarchical
     * taxonomy and its {@code assign_terms} for a flat one. The name is required ({@code empty_term_name}) and is
     * stored as WordPress's save filters leave it ({@code invalid_term_name} when nothing is left); the description
     * is filtered with kses; the slug is the given one, or the name's, sanitized and made unique. A parent is refused
     * for a flat taxonomy ({@code rest_taxonomy_not_hierarchical}) and must be a term of the taxonomy on this site
     * ({@code missing_parent}). A name equal to a sibling's, ignoring the case of A to Z, is refused with
     * {@code term_exists} unless an unused slug is given. Announced with {@link TermCreated}.
     */
    TermSnapshot create(String taxonomy, TermChanges changes);

    /**
     * Updates a term (WordPress's {@code wp_update_term}): the stored term merged with the changes and saved again, so
     * every field is filtered again. Needs {@code edit_term} on the term. A term that is missing, of another site or of
     * another taxonomy is {@code invalid_term}. Renaming keeps the slug; an empty slug generates it from the name. A
     * slug another term of the taxonomy holds is refused with {@code duplicate_term_slug}, unless the slug was
     * generated or the parent changes, in which case it is made unique. The parent follows the rules of
     * {@link #create}; a parent that would make the term its own ancestor is dropped (the term goes to the top level,
     * without error), and a loop further up the parent's chain is cut by leaving each of its terms without parent
     * (each announced as updated). A value its column cannot hold is refused with {@code db_update_error} (WordPress
     * would keep the old name and slug and save the rest). With {@code expectedVersion}, a term that changed since
     * that version is refused with {@code jakarta.persistence.OptimisticLockException} before any write, leaving the
     * caller's transaction usable; a change of the term's count does not change its version. Announced with
     * {@link TermUpdated}.
     */
    TermSnapshot update(TermId term, String taxonomy, TermChanges changes);
}
