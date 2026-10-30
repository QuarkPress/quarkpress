package org.quarkpress.taxonomy;

import io.quarkus.security.identity.SecurityIdentity;
import org.quarkpress.core.PostId;
import org.quarkpress.core.TermId;

/**
 * Capability checks about terms and taxonomies, for the site the caller is bound to. Each check without an identity is
 * made for the current identity. Checks read the database and never throw for a refusal; a taxonomy that is not
 * registered is refused.
 */
public interface TermPermissions {

    /** Creating a term: a hierarchical taxonomy's {@code edit_terms}, a flat one's {@code assign_terms}. */
    boolean canCreate(String taxonomy);

    boolean canCreate(SecurityIdentity identity, String taxonomy);

    /** {@code edit_term}; false for a term that does not exist. */
    boolean canEdit(TermId term);

    boolean canEdit(SecurityIdentity identity, TermId term);

    /** {@code delete_term}; false for a term that does not exist, and never for the taxonomy's default term. */
    boolean canDelete(TermId term);

    boolean canDelete(SecurityIdentity identity, TermId term);

    /** The taxonomy's {@code edit_terms}. */
    boolean canEditTerms(String taxonomy);

    boolean canEditTerms(SecurityIdentity identity, String taxonomy);

    /** The taxonomy's {@code assign_terms}. */
    boolean canAssign(String taxonomy);

    boolean canAssign(SecurityIdentity identity, String taxonomy);

    /**
     * Whether the terms of {@code post} in {@code taxonomy} may be listed: {@link PostTermsAccess#MISSING_POST} for no
     * such post; {@link PostTermsAccess#DENIED} when the post's type does not use the taxonomy, or when the post is
     * neither publicly viewable (a viewable type and a viewable status) nor readable ({@code read_post}); else
     * {@link PostTermsAccess#ALLOWED}.
     */
    PostTermsAccess postTermsAccess(PostId post, String taxonomy);

    PostTermsAccess postTermsAccess(SecurityIdentity identity, PostId post, String taxonomy);
}
