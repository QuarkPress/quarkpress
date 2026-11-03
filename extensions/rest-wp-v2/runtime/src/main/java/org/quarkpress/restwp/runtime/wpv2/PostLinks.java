package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.core.PostId;
import org.quarkpress.core.PostTypeDefinition;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.posts.runtime.PostEntity;
import org.quarkpress.posts.runtime.PostRepository;
import org.quarkpress.restwp.Links;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.RestUrls;
import org.quarkpress.security.Capabilities;
import org.quarkpress.taxonomy.TermPermissions;

/**
 * The links of a post as WordPress's posts controller adds them, in its order: the post itself, its collection, its
 * type, its author (embeddable, when the type has authors and the post one), its comments (embeddable, when the type
 * has comments), its revisions with their count and, when there are any, the newest one (types with revisions, when
 * the revisions module is present; the count is read as the system, so it is the count of every revision, autosaves
 * included, and 0 when revisions are off for the post), its parent (embeddable, hierarchical types with a parent
 * whose type is shown in REST), its attachments, and one embeddable term link per taxonomy of the type shown in REST
 * (none when the type has no such taxonomy). In the edit context the actions the caller may take follow, each pointing at the post itself:
 * publishing, unfiltered HTML, making a post sticky (needs the type's {@code edit_others_posts} and
 * {@code publish_posts}), changing the author ({@code edit_others_posts} on a type with authors), then per REST
 * taxonomy creating and assigning terms. The featured image is not linked until the media routes exist.
 */
@ApplicationScoped
class PostLinks {

    private static final String API = "https://api.w.org/";

    private static final String NAMESPACE = "wp/v2";

    @Inject
    RestUrls urls;

    @Inject
    SiteContext site;

    @Inject
    PostTypes types;

    @Inject
    PostRepository rows;

    @Inject
    PostPermissions permissions;

    @Inject
    TermPermissions termPermissions;

    @Inject
    Capabilities capabilities;

    /** The revision history; a bean only while the revisions module is present. */
    @Inject
    Instance<RevisionHistory> revisions;

    /** The post's links for {@code request}: the action links only in the edit context. */
    Links of(PostRoutes routes, PostSnapshot post, RestRequest request) {
        String self = urls.route(routes.item(post.id()));
        Links links = Links.none()
                .add("self", self)
                .add("collection", urls.route(routes.collection()))
                .add("about", urls.route("/" + NAMESPACE + "/types/" + routes.key()));
        Optional<UserId> author = post.author();
        if (routes.supports("author") && author.isPresent()) {
            links = links.add(
                    "author",
                    urls.route("/" + NAMESPACE + "/users/" + author.get().value()),
                    embeddable());
        }
        if (routes.supports("comments")) {
            links = links.add(
                    "replies", urls.route("/" + NAMESPACE + "/comments", Map.of("post", id(post))), embeddable());
        }
        if (routes.supports("revisions") && revisions.isResolvable()) {
            RevisionHistory.Count history = revisions.get().of(post.id());
            String route = routes.item(post.id()) + "/revisions";
            links = links.add("version-history", urls.route(route), Map.of("count", LongNode.valueOf(history.count())));
            if (history.count() > 0 && history.latest() > 0) {
                links = links.add(
                        "predecessor-version",
                        urls.route(route + "/" + history.latest()),
                        Map.of("id", LongNode.valueOf(history.latest())));
            }
        }
        if (routes.type().hierarchical() && post.parent().isPresent()) {
            Optional<String> parent = parentRoute(routes, post.parent().get());
            if (parent.isPresent()) {
                links = links.add("up", urls.route(parent.get()), embeddable());
            }
        }
        links = links.add(API + "attachment", urls.route("/" + NAMESPACE + "/media", Map.of("parent", id(post))));
        for (TaxonomyDefinition taxonomy : routes.taxonomies()) {
            Map<String, JsonNode> attributes = new LinkedHashMap<>();
            attributes.put("taxonomy", TextNode.valueOf(taxonomy.key()));
            attributes.put("embeddable", BooleanNode.TRUE);
            links = links.add(
                    API + "term",
                    urls.route("/" + NAMESPACE + "/" + PostRoutes.base(taxonomy), Map.of("post", id(post))),
                    attributes);
        }
        if (request.isEditContext()) {
            links = actions(links, routes, request.identity(), self);
        }
        return links;
    }

    /** The action links of the caller, each pointing at the post itself. */
    private Links actions(Links links, PostRoutes routes, SecurityIdentity caller, String self) {
        String type = routes.key();
        boolean publish = permissions.canType(caller, "publish_posts", type);
        if (publish) {
            links = links.add(API + "action-publish", self);
        }
        if (capabilities.can(caller, "unfiltered_html", Optional.empty())) {
            links = links.add(API + "action-unfiltered-html", self);
        }
        boolean others = permissions.canType(caller, "edit_others_posts", type);
        if (type.equals("post") && others && publish) {
            links = links.add(API + "action-sticky", self);
        }
        if (routes.supports("author") && others) {
            links = links.add(API + "action-assign-author", self);
        }
        for (TaxonomyDefinition taxonomy : routes.taxonomies()) {
            String base = PostRoutes.base(taxonomy);
            if (termPermissions.canCreate(caller, taxonomy.key())) {
                links = links.add(API + "action-create-" + base, self);
            }
            if (termPermissions.canAssign(caller, taxonomy.key())) {
                links = links.add(API + "action-assign-" + base, self);
            }
        }
        return links;
    }

    /**
     * The route of the parent in its own type, read on this site: the post's own type's route when the parent is not
     * found; none when the parent's type is not registered or not shown in REST.
     */
    private Optional<String> parentRoute(PostRoutes routes, PostId parent) {
        Optional<PostSnapshot> found = rows.findPost(site.siteId(), parent).map(PostEntity::toSnapshot);
        if (found.isEmpty()) {
            return Optional.of(routes.item(parent));
        }
        if (found.get().type().equals(routes.key())) {
            return Optional.of(routes.item(parent));
        }
        return types.find(found.get().type())
                .filter(PostTypeDefinition::showInRest)
                .map(type -> "/" + TypeExtras.of(type.key()).restNamespace() + "/" + PostRoutes.base(type) + "/"
                        + parent.value());
    }

    private static Map<String, JsonNode> embeddable() {
        return Map.of("embeddable", BooleanNode.TRUE);
    }

    private static String id(PostSnapshot post) {
        return Long.toString(post.id().value());
    }
}
