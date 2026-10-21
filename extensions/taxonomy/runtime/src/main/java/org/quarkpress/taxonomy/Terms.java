package org.quarkpress.taxonomy;

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
}
