package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.function.Function;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.LockReleaseTokens;
import org.quarkpress.posts.PostLocks;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.posts.runtime.PostEntity;
import org.quarkpress.posts.runtime.PostRepository;
import org.quarkpress.restwp.Arg;
import org.quarkpress.restwp.Endpoint;
import org.quarkpress.restwp.PermissionResult;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestException;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.RestResponse;
import org.quarkpress.restwp.RestRouteProvider;
import org.quarkpress.restwp.RouteRegistry;
import org.quarkpress.restwp.runtime.PhpValues;
import org.quarkpress.restwp.runtime.RestJson;
import org.quarkpress.security.QuarkPressPrincipal;
import org.quarkpress.security.UserProfile;
import org.quarkpress.security.UserProfiles;

/**
 * The editor's post lock over REST, {@code /quarkpress/v1/locks/<id>}, answering what WordPress's edit screen,
 * heartbeat and admin-ajax give the editor, so the admin shell can render the same dialogs. {@code POST} takes the
 * lock on opening the post: {@code {isLocked: false, activePostLock: "<time>:<user>", release_token}} or, when
 * another user holds a fresh lock, {@code {isLocked: true, user: {name, avatar}}} with nothing taken (the avatar is
 * 128 pixels, only when the site shows avatars). {@code PUT} is the heartbeat's refresh: {@code {new_lock,
 * release_token}}, or {@code {lock_error: {name, text, avatar_src, avatar_src_2x}}} (64 and 128 pixels) when another
 * user has taken over. {@code POST .../takeover} takes the lock whatever is stored. {@code DELETE} with
 * {@code active_post_lock} releases the caller's own lock by compare-and-set and answers {@code {released: bool}},
 * false when the stored value no longer matches.
 *
 * <p>Every route needs {@code edit_post} on a post of this site whose type is shown in REST; a missing post is
 * {@code rest_post_invalid_id}. The {@code release_token} of every take, refresh and takeover answer stands in for
 * the caller on the release the editor sends when the page is left: a browser's {@code sendBeacon} cannot send
 * {@code DELETE} nor an {@code Authorization} header, so the shell posts a JSON blob to {@code ...?_method=DELETE}
 * with {@code active_post_lock} and {@code release_token}, and a token valid for that post, the user of the lock value
 * and the value itself (consumed either way, as it is one-time) releases as that user would. A caller with a Bearer
 * token releases with its own rights and the token in its body is left alone.
 */
@ApplicationScoped
public class LocksController implements RestRouteProvider {

    private static final String NAMESPACE = "quarkpress/v1";

    private static final String LOCK = "/locks/(?P<id>[\\d]+)";

    private static final String TAKEOVER = LOCK + "/takeover";

    private static final int DIALOG_AVATAR = 128;

    private static final int NOTICE_AVATAR = 64;

    @Inject
    PostLocks locks;

    @Inject
    LockReleaseTokens tokens;

    @Inject
    PostPermissions permissions;

    @Inject
    PostRepository posts;

    @Inject
    PostTypes types;

    @Inject
    UserProfiles profiles;

    @Inject
    SiteContext site;

    @Inject
    CurrentIdentityAssociation association;

    @Override
    public int order() {
        return 1000;
    }

    @Override
    public void register(RouteRegistry routes) {
        routes.route(NAMESPACE, LOCK)
                .arg(idArg())
                .endpoint(Endpoint.of("POST")
                        .permission(this::mayEdit)
                        .handler(this::take)
                        .build())
                .endpoint(Endpoint.of("PUT")
                        .permission(this::mayEdit)
                        .handler(this::refresh)
                        .build())
                .endpoint(Endpoint.of("DELETE")
                        .arg(Arg.of("active_post_lock", "string")
                                .description("The lock value the editor holds, as the take or refresh answered it.")
                                .build())
                        .arg(Arg.of("release_token", "string")
                                .description("The one-time release token of that lock value, for a caller without"
                                        + " credentials.")
                                .build())
                        .permission(this::mayRelease)
                        .handler(this::release)
                        .build())
                .register();
        routes.route(NAMESPACE, TAKEOVER)
                .arg(idArg())
                .endpoint(Endpoint.of("POST")
                        .permission(this::mayEdit)
                        .handler(this::takeOver)
                        .build())
                .register();
    }

    // ---- permissions

    /** {@code edit_post} on the post for the request's caller. */
    private PermissionResult mayEdit(RestRequest request) {
        return mayEdit(request, request.identity());
    }

    /**
     * A caller with credentials releases with its own rights; without any, the body's release token is checked in
     * the handler, where it stands in for the caller.
     */
    private PermissionResult mayRelease(RestRequest request) {
        return request.user().isPresent() ? mayEdit(request) : PermissionResult.allowed();
    }

    private PermissionResult mayEdit(RestRequest request, SecurityIdentity identity) {
        return requested(request)
                .map(id -> mayEdit(id, identity, cannotEdit(request)))
                .orElseGet(() -> PermissionResult.denied(invalidId()));
    }

    /** {@code edit_post} on the post for {@code identity}; {@code refusal} is the answer when it lacks it. */
    private PermissionResult mayEdit(PostId id, SecurityIdentity identity, RestError refusal) {
        Optional<PostSnapshot> post = existing(id);
        if (post.isEmpty()) {
            return PermissionResult.denied(invalidId());
        }
        if (!permissions.can(identity, "edit_post", post.get())) {
            return PermissionResult.denied(refusal);
        }
        return PermissionResult.allowed();
    }

    // ---- handlers

    private RestResponse take(RestRequest request) {
        PostId post = post(request);
        return switch (locks.take(post)) {
            case PostLocks.Take.Taken taken -> RestResponse.ok(taken(post, taken.lock()));
            case PostLocks.Take.Held held -> RestResponse.ok(heldBy(held.holder()));
        };
    }

    private RestResponse refresh(RestRequest request) {
        PostId post = post(request);
        return switch (locks.refresh(post)) {
            case PostLocks.Take.Taken taken -> RestResponse.ok(refreshed(post, taken.lock()));
            case PostLocks.Take.Held held -> RestResponse.ok(takenOverBy(held.holder()));
        };
    }

    private RestResponse takeOver(RestRequest request) {
        PostId post = post(request);
        return RestResponse.ok(taken(post, locks.takeOver(post)));
    }

    private RestResponse release(RestRequest request) {
        String value = text(request, "active_post_lock");
        if (request.user().isPresent()) {
            return released(locks.release(post(request), value));
        }
        // nobody is authenticated: the one-time token of this lock value stands in for the user of the value
        String token = text(request, "release_token");
        Optional<UserProfile> user =
                PostLocks.Lock.parse(value).map(PostLocks.Lock::user).flatMap(profiles::find);
        // the refusal is the anonymous caller's (401), decided before the request runs as the token's user
        RestError refusal = cannotEdit(request);
        Optional<PostId> id = requested(request);
        if (token.isEmpty() || user.isEmpty() || id.isEmpty()) {
            throw new RestException(refusal);
        }
        PostId post = id.get();
        return as(user.get(), identity -> {
            if (!tokens.consume(post, user.get().id(), token, value)) {
                throw new RestException(refusal);
            }
            if (mayEdit(post, identity, refusal) instanceof PermissionResult.Denied denied) {
                throw new RestException(denied.error());
            }
            return released(locks.release(post, value));
        });
    }

    // ---- answers

    private ObjectNode taken(PostId post, PostLocks.Lock lock) {
        ObjectNode answer = RestJson.NODES.objectNode();
        answer.put("isLocked", false);
        answer.put("activePostLock", lock.value());
        answer.put("release_token", tokens.issue(post, lock.user(), lock.value()));
        return answer;
    }

    private ObjectNode refreshed(PostId post, PostLocks.Lock lock) {
        ObjectNode answer = RestJson.NODES.objectNode();
        answer.put("new_lock", lock.value());
        answer.put("release_token", tokens.issue(post, lock.user(), lock.value()));
        return answer;
    }

    /** WordPress's {@code postLock} editor setting when another user holds the post. */
    private ObjectNode heldBy(UserId holder) {
        Optional<UserProfile> profile = profiles.find(holder);
        ObjectNode answer = RestJson.NODES.objectNode();
        answer.put("isLocked", true);
        ObjectNode user = answer.putObject("user");
        user.put("name", name(profile));
        avatar(profile, DIALOG_AVATAR).ifPresent(url -> user.put("avatar", url));
        return answer;
    }

    /** WordPress's heartbeat {@code lock_error} when another user has taken over. */
    private ObjectNode takenOverBy(UserId holder) {
        Optional<UserProfile> profile = profiles.find(holder);
        ObjectNode answer = RestJson.NODES.objectNode();
        ObjectNode error = answer.putObject("lock_error");
        String name = name(profile);
        error.put("name", name);
        error.put("text", name + " has taken over and is currently editing.");
        avatar(profile, NOTICE_AVATAR).ifPresent(url -> error.put("avatar_src", url));
        avatar(profile, DIALOG_AVATAR).ifPresent(url -> error.put("avatar_src_2x", url));
        return answer;
    }

    private static RestResponse released(boolean released) {
        ObjectNode answer = RestJson.NODES.objectNode();
        answer.put("released", released);
        return RestResponse.ok(answer);
    }

    private static String name(Optional<UserProfile> profile) {
        return profile.map(UserProfile::displayName).orElse("");
    }

    private Optional<String> avatar(Optional<UserProfile> profile, int size) {
        Optional<String> email = profile.flatMap(UserProfile::email);
        String name = profile.map(Avatars::initialsName).orElse("");
        return Avatars.url(email, name, size, site.options());
    }

    // ---- lookups

    /**
     * Runs {@code work} with {@code user} as the current identity, the one the posts services check, and puts the
     * request's identity back afterwards.
     */
    private <T> T as(UserProfile user, Function<SecurityIdentity, T> work) {
        SecurityIdentity previous = association.getIdentity();
        SecurityIdentity identity = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(user.subject()))
                .addAttribute(QuarkPressPrincipal.ATTRIBUTE, new QuarkPressPrincipal(user.id(), user.subject()))
                .build();
        association.setIdentity(identity);
        try {
            return work.apply(identity);
        } finally {
            association.setIdentity(previous);
        }
    }

    /** The post of this site with this id whose type is shown in REST, read without any capability check. */
    private Optional<PostSnapshot> existing(PostId id) {
        return posts.findPost(site.siteId(), id)
                .map(PostEntity::toSnapshot)
                .filter(post ->
                        types.find(post.type()).map(type -> type.showInRest()).orElse(false));
    }

    /** The route's post id; empty for {@code 0}, which the digits-only group admits and no post has. */
    private static Optional<PostId> requested(RestRequest request) {
        long id = request.param("id").map(PhpValues::toLong).orElse(0L);
        return id > 0 ? Optional.of(PostId.of(id)) : Optional.empty();
    }

    /** The route's post id once the permission check found the post. */
    private static PostId post(RestRequest request) {
        return requested(request).orElseThrow(() -> new RestException(invalidId()));
    }

    private static String text(RestRequest request, String name) {
        return request.param(name)
                .filter(JsonNode::isTextual)
                .map(JsonNode::textValue)
                .orElse("");
    }

    private static Arg idArg() {
        return Arg.of("id", "integer")
                .description("Unique identifier for the post.")
                .phase(Arg.Phase.SANITIZE)
                .build();
    }

    private static RestError invalidId() {
        return RestError.of("rest_post_invalid_id", "Invalid post ID.", 404);
    }

    private static RestError cannotEdit(RestRequest request) {
        return RestError.of("rest_cannot_edit", "Sorry, you are not allowed to edit this post.", request.authCode());
    }
}
