package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.function.Predicate;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.core.TermId;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.restwp.PermissionResult;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.runtime.ArgChecks;
import org.quarkpress.restwp.runtime.PhpValues;
import org.quarkpress.taxonomy.TermPermissions;
import org.quarkpress.taxonomy.Terms;

/**
 * WordPress's update checks of a post, shared by every route that writes a stored post through the posts
 * preparation (the item's update and the autosave): the post of the request's {@code id}, then the caller's
 * {@code edit_post}, another author, a changed stickiness and the named terms, in that order. The other-author and
 * terms rules serve the create check too.
 */
@ApplicationScoped
class PostUpdateChecks {

    @Inject
    PostPermissions permissions;

    @Inject
    Terms terms;

    @Inject
    TermPermissions termPermissions;

    @Inject
    PostsController.ItemReads reads;

    /**
     * WordPress's update checks, in its order: the post of the request's {@code id} must exist under the routes'
     * type; the caller needs {@code edit_post} on it; an author other than the caller needs the type's
     * {@code edit_others_posts}; a {@code sticky} that differs from the post's stickiness, for any type, needs its
     * {@code edit_others_posts} and {@code publish_posts}; then each named term that exists in its taxonomy must be
     * assignable.
     */
    PermissionResult update(PostRoutes routes, RestRequest request) {
        return update(
                routes,
                request,
                reads.post(routes, request, "id"),
                post -> permissions.can(request.identity(), "edit_post", post));
    }

    /**
     * The update checks of {@link #update(PostRoutes, RestRequest)}, with the post of the request's {@code id} already
     * looked up ({@code found}, empty when there is none) and {@code edit_post} decided by {@code mayEdit}: for routes
     * that read their posts through reads of their own.
     */
    PermissionResult update(
            PostRoutes routes, RestRequest request, Optional<PostSnapshot> found, Predicate<PostSnapshot> mayEdit) {
        if (found.isEmpty()) {
            return PermissionResult.denied(RestError.of("rest_post_invalid_id", "Invalid post ID.", 404));
        }
        PostSnapshot post = found.get();
        SecurityIdentity caller = request.identity();
        if (!mayEdit.test(post)) {
            return PermissionResult.denied(RestError.of(
                    "rest_cannot_edit", "Sorry, you are not allowed to edit this post.", request.authCode()));
        }
        if (otherAuthor(request) && !permissions.canType(caller, "edit_others_posts", routes.key())) {
            return PermissionResult.denied(RestError.of(
                    "rest_cannot_edit_others",
                    "Sorry, you are not allowed to update posts as this user.",
                    request.authCode()));
        }
        // a type without the field leaves the value unsanitized: PHP's truthiness decides
        Optional<JsonNode> sticky = request.param("sticky");
        if (sticky.isPresent()
                && PhpValues.toBoolean(sticky.get()) != post.sticky()
                && !(permissions.canType(caller, "edit_others_posts", routes.key())
                        && permissions.canType(caller, "publish_posts", routes.key()))) {
            return PermissionResult.denied(RestError.of(
                    "rest_cannot_assign_sticky",
                    "Sorry, you are not allowed to make posts sticky.",
                    request.authCode()));
        }
        if (!mayAssignTerms(routes, request)) {
            return PermissionResult.denied(cannotAssignTerm(request));
        }
        return PermissionResult.allowed();
    }

    /** Whether the request names an author (one not empty in PHP's sense) other than the caller. */
    static boolean otherAuthor(RestRequest request) {
        Optional<JsonNode> author = request.param("author").filter(given -> !PhpValues.isEmpty(given));
        return author.isPresent()
                && !request.user()
                        .map(user -> user.value() == PhpValues.toLong(author.get()))
                        .orElse(false);
    }

    /** Whether every named term that exists in its taxonomy on this site may be assigned by the caller. */
    boolean mayAssignTerms(PostRoutes routes, RestRequest request) {
        for (TaxonomyDefinition taxonomy : routes.taxonomies()) {
            Optional<JsonNode> value = request.param(PostRoutes.base(taxonomy));
            if (value.isEmpty()
                    || !value.get().isContainerNode()
                    || termPermissions.canAssign(request.identity(), taxonomy.key())) {
                continue;
            }
            for (JsonNode item : PhpValues.values(value.get())) {
                long id = PhpValues.toLong(item);
                if (id > 0 && terms.find(TermId.of(id), taxonomy.key()).isPresent()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** WordPress's refusal of a term the caller may not assign. */
    static RestError cannotAssignTerm(RestRequest request) {
        return RestError.of(
                "rest_cannot_assign_term",
                "Sorry, you are not allowed to assign the provided terms.",
                request.authCode());
    }

    /**
     * The stored post of the request whose arguments are being checked (its {@code id}), for the status and template
     * checks, which accept the stored value; none when there is no such post or it cannot be read, so that those
     * checks fall back to their lists (never an error of their own) and the permission check answers for the post.
     */
    Optional<PostSnapshot> storedWhileChecking(PostRoutes routes) {
        Optional<RestRequest> request = ArgChecks.checking();
        if (request.isEmpty()) {
            return Optional.empty();
        }
        try {
            return reads.post(routes, request.get(), "id");
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }
}
