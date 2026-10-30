package org.quarkpress.posts;

import io.quarkus.security.identity.SecurityIdentity;
import java.util.List;
import org.quarkpress.core.PostId;
import org.quarkpress.core.PostStatusDefinition;
import org.quarkpress.core.PostTypeDefinition;

/**
 * Capability checks about posts, post types and post statuses, for the site the caller is bound to. Each check without
 * an identity is made for the current identity. Checks read the database and never throw for a refusal.
 */
public interface PostPermissions {

    /**
     * WordPress's {@code current_user_can} for a meta capability ({@code edit_post}, {@code read_post},
     * {@code delete_post}, ...) of a post of the bound site; a post that does not exist is checked as a missing object,
     * which every such capability refuses. A public post is not readable here by a logged-out caller: WordPress's REST
     * read rule for public posts is the status rule of the controllers, not {@code read_post}.
     */
    boolean can(String metaCapability, PostId post);

    boolean can(SecurityIdentity identity, String metaCapability, PostId post);

    /**
     * {@link #can(String, PostId)} for a post already read; a post of another site than the bound one is checked as a
     * missing object.
     */
    boolean can(String metaCapability, PostSnapshot post);

    boolean can(SecurityIdentity identity, String metaCapability, PostSnapshot post);

    /**
     * A type capability by its generic name ({@code edit_posts}, {@code create_posts}, {@code publish_posts},
     * {@code edit_others_posts}, ...) mapped through the type's names; false for an unregistered type, and false for a
     * name the type does not have (one that only types mapping meta capabilities have). An unknown generic name is
     * refused with {@code IllegalArgumentException}.
     */
    boolean canType(String genericCapability, String type);

    boolean canType(SecurityIdentity identity, String genericCapability, String type);

    /** The type's {@code create_posts}. */
    boolean canCreate(String type);

    boolean canCreate(SecurityIdentity identity, String type);

    /** The type's {@code publish_posts}. */
    boolean canPublish(String type);

    boolean canPublish(SecurityIdentity identity, String type);

    /** The type's {@code edit_others_posts}. */
    boolean canEditOthers(String type);

    boolean canEditOthers(SecurityIdentity identity, String type);

    /** {@code edit_posts} of at least one type registered to show in REST. */
    boolean canEditSomeRestType();

    boolean canEditSomeRestType(SecurityIdentity identity);

    /** The same, among the REST types that support {@code feature} (such as {@code author}). */
    boolean canEditSomeRestTypeSupporting(String feature);

    boolean canEditSomeRestTypeSupporting(SecurityIdentity identity, String feature);

    /**
     * WordPress's REST status visibility: a public status always; a status that is not internal, or trash, with
     * {@link #canEditSomeRestType()}; any other status (internal, or not registered) never, for anyone.
     */
    boolean statusVisible(String status);

    boolean statusVisible(SecurityIdentity identity, String status);

    /**
     * The statuses the REST statuses collection lists: the registered ones that are not internal, in registration
     * order, then trash. The same for every identity, so it has no form taking one.
     */
    List<String> restStatuses();

    /** WordPress's viewable post type: publicly queryable, or built in and public. */
    static boolean typeViewable(PostTypeDefinition type) {
        return type.publiclyQueryable() || (type.builtIn() && type.publicly());
    }

    /**
     * WordPress's viewable post status: neither internal nor protected, and publicly queryable, or built in and
     * public.
     */
    static boolean statusViewable(PostStatusDefinition status) {
        return !status.internal()
                && !status.protectedStatus()
                && (status.publiclyQueryable() || (status.builtIn() && status.publicly()));
    }
}
