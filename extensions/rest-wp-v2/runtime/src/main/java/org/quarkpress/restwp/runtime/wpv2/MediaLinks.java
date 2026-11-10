package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.core.PostId;
import org.quarkpress.core.PostTypeDefinition;
import org.quarkpress.core.UserId;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.posts.FeaturedImages;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostStatuses;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.posts.runtime.PostCapabilityFacts;
import org.quarkpress.restwp.Links;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.RestUrls;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.PostFacts;

/**
 * The links of an attachment as WordPress's attachments controller adds them, in its order: the attachment itself,
 * its collection, its type, its author (embeddable, when it has one), its comments (embeddable, always), its cover
 * image (embeddable, when it names one that exists and is published in effect or readable by the caller; the API
 * root when its type is not shown in REST), and the post it is attached to (embeddable, with that post's type and
 * id, whatever its status, when the post exists; the API root itself when the post's type is not shown in REST, as
 * WordPress links it). In the edit context the actions the caller may take follow, each pointing at the attachment:
 * unfiltered HTML, then changing the author (the attachment type's {@code edit_others_posts}). Attachments are never linked to revisions, a parent in their own type,
 * attachments of their own, publishing or making sticky. The parent and the cover image are read once per request,
 * however many attachments of a response name them.
 */
@ApplicationScoped
public class MediaLinks {

    private static final String API = "https://api.w.org/";

    private static final String NAMESPACE = "wp/v2";

    @Inject
    RestUrls urls;

    @Inject
    PostTypes types;

    @Inject
    MediaReads reads;

    @Inject
    PostPermissions permissions;

    @Inject
    PostStatuses statuses;

    @Inject
    Capabilities capabilities;

    /** The attachment's links for {@code request}: the action links only in the edit context. */
    Links of(PostRoutes routes, MediaSnapshot media, RestRequest request) {
        PostSnapshot attachment = media.post();
        String id = Long.toString(attachment.id().value());
        String self = urls.route(routes.item(attachment.id()));
        Links links = Links.none()
                .add("self", self)
                .add("collection", urls.route(routes.collection()))
                .add("about", urls.route("/" + NAMESPACE + "/types/" + routes.key()));
        Optional<UserId> author = attachment.author();
        if (author.isPresent() && author.get().value() != 0) {
            links = links.add(
                    "author",
                    urls.route("/" + NAMESPACE + "/users/" + author.get().value()),
                    embeddable());
        }
        links = links.add("replies", urls.route("/" + NAMESPACE + "/comments", Map.of("post", id)), embeddable());
        Optional<String> cover = featuredMedia(attachment, request.identity());
        if (cover.isPresent()) {
            links = links.add(API + "featuredmedia", cover.get(), embeddable());
        }
        Optional<PostSnapshot> parent = attachment.parent().flatMap(reads::post);
        if (parent.isPresent()) {
            Map<String, JsonNode> attributes = new LinkedHashMap<>();
            attributes.put("embeddable", BooleanNode.TRUE);
            attributes.put("post_type", TextNode.valueOf(parent.get().type()));
            attributes.put("id", LongNode.valueOf(parent.get().id().value()));
            links = links.add(
                    API + "attached-to", urls.route(route(parent.get()).orElse("")), attributes);
        }
        if (request.isEditContext()) {
            SecurityIdentity caller = request.identity();
            if (capabilities.can(caller, "unfiltered_html", Optional.empty())) {
                links = links.add(API + "action-unfiltered-html", self);
            }
            if (permissions.canType(caller, "edit_others_posts", routes.key())) {
                links = links.add(API + "action-assign-author", self);
            }
        }
        return links;
    }

    /**
     * The URL of the featured image (an attachment's cover image) that {@code post} names, when it names one that
     * exists on this site and that is published in effect or that the caller may read: its route in its own type, or
     * the API root itself when its type is not shown in REST, as WordPress links it.
     * The image, and the post it inherits its status from, are read once per request, however many posts name them.
     */
    Optional<String> featuredMedia(PostSnapshot post, SecurityIdentity caller) {
        long featured = FeaturedImages.of(post);
        if (featured <= 0) {
            return Optional.empty();
        }
        Optional<PostSnapshot> target = reads.post(PostId.of(featured));
        if (target.isEmpty()) {
            return Optional.empty();
        }
        PostFacts targetFacts = PostCapabilityFacts.facts(target.get(), types, statuses, reads::post);
        boolean visible = targetFacts.effectiveStatus().equals("publish")
                || capabilities.can(caller, "read_post", Optional.of(targetFacts));
        if (!visible) {
            return Optional.empty();
        }
        return Optional.of(urls.route(route(target.get()).orElse("")));
    }

    /** The REST route of {@code post} in its own type; none when its type is not registered or not shown in REST. */
    private Optional<String> route(PostSnapshot post) {
        return types.find(post.type())
                .filter(PostTypeDefinition::showInRest)
                .map(type -> PostRoutes.collection(type) + "/" + post.id().value());
    }

    private static Map<String, JsonNode> embeddable() {
        return Map.of("embeddable", BooleanNode.TRUE);
    }
}
