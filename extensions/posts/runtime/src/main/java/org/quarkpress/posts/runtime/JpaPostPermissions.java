package org.quarkpress.posts.runtime;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.quarkpress.core.PostId;
import org.quarkpress.core.PostStatusDefinition;
import org.quarkpress.core.PostTypeDefinition;
import org.quarkpress.core.SiteContext;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostStatuses;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.security.CapObject;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.MissingObject;
import org.quarkpress.security.PostTypeCapabilities;

/** Post capability checks answered by security's {@code Capabilities} with the facts of stored posts. */
@ApplicationScoped
public class JpaPostPermissions implements PostPermissions {

    @Inject
    Capabilities capabilities;

    @Inject
    PostCapabilityFacts facts;

    @Inject
    PostRepository posts;

    @Inject
    PostTypes types;

    @Inject
    PostStatuses statuses;

    @Inject
    SiteContext site;

    @Inject
    Acting acting;

    @Override
    public boolean can(String metaCapability, PostId post) {
        return can(acting.identity(), metaCapability, post);
    }

    @Override
    @Transactional
    public boolean can(SecurityIdentity identity, String metaCapability, PostId post) {
        Objects.requireNonNull(metaCapability, "metaCapability");
        Objects.requireNonNull(post, "post");
        CapObject object = posts.findPost(site.siteId(), post)
                .<CapObject>map(row -> facts.of(row.toSnapshot()))
                .orElseGet(() -> new MissingObject(post.value()));
        return capabilities.can(identity, metaCapability, Optional.of(object));
    }

    @Override
    public boolean can(String metaCapability, PostSnapshot post) {
        return can(acting.identity(), metaCapability, post);
    }

    @Override
    @Transactional
    public boolean can(SecurityIdentity identity, String metaCapability, PostSnapshot post) {
        Objects.requireNonNull(metaCapability, "metaCapability");
        Objects.requireNonNull(post, "post");
        // another site's post is invisible here, as for the id form: never checked against this site's roles
        CapObject object = post.site().equals(site.siteId())
                ? facts.of(post)
                : new MissingObject(post.id().value());
        return capabilities.can(identity, metaCapability, Optional.of(object));
    }

    @Override
    public boolean canType(String genericCapability, String type) {
        return canType(acting.identity(), genericCapability, type);
    }

    @Override
    public boolean canType(SecurityIdentity identity, String genericCapability, String type) {
        Function<PostTypeCapabilities, String> name = typeName(genericCapability);
        return types.capabilities(type)
                .map(name)
                .map(capability -> capabilities.can(identity, capability, Optional.empty()))
                .orElse(false);
    }

    /** The accessor of a type's own name for a generic type capability. */
    private static Function<PostTypeCapabilities, String> typeName(String genericCapability) {
        Objects.requireNonNull(genericCapability, "genericCapability");
        return switch (genericCapability) {
            case "edit_posts" -> PostTypeCapabilities::editPosts;
            case "edit_others_posts" -> PostTypeCapabilities::editOthersPosts;
            case "delete_posts" -> PostTypeCapabilities::deletePosts;
            case "publish_posts" -> PostTypeCapabilities::publishPosts;
            case "read_private_posts" -> PostTypeCapabilities::readPrivatePosts;
            case "create_posts" -> PostTypeCapabilities::createPosts;
            case "read" -> PostTypeCapabilities::read;
            case "delete_private_posts" -> PostTypeCapabilities::deletePrivatePosts;
            case "delete_published_posts" -> PostTypeCapabilities::deletePublishedPosts;
            case "delete_others_posts" -> PostTypeCapabilities::deleteOthersPosts;
            case "edit_private_posts" -> PostTypeCapabilities::editPrivatePosts;
            case "edit_published_posts" -> PostTypeCapabilities::editPublishedPosts;
            default -> throw new IllegalArgumentException("not a generic post type capability: " + genericCapability);
        };
    }

    @Override
    public boolean canCreate(String type) {
        return canType("create_posts", type);
    }

    @Override
    public boolean canCreate(SecurityIdentity identity, String type) {
        return canType(identity, "create_posts", type);
    }

    @Override
    public boolean canPublish(String type) {
        return canType("publish_posts", type);
    }

    @Override
    public boolean canPublish(SecurityIdentity identity, String type) {
        return canType(identity, "publish_posts", type);
    }

    @Override
    public boolean canEditOthers(String type) {
        return canType("edit_others_posts", type);
    }

    @Override
    public boolean canEditOthers(SecurityIdentity identity, String type) {
        return canType(identity, "edit_others_posts", type);
    }

    @Override
    public boolean canEditSomeRestType() {
        return canEditSomeRestType(acting.identity());
    }

    @Override
    public boolean canEditSomeRestType(SecurityIdentity identity) {
        return canEditSomeRestType(identity, Optional.empty());
    }

    @Override
    public boolean canEditSomeRestTypeSupporting(String feature) {
        return canEditSomeRestTypeSupporting(acting.identity(), feature);
    }

    @Override
    public boolean canEditSomeRestTypeSupporting(SecurityIdentity identity, String feature) {
        return canEditSomeRestType(identity, Optional.of(Objects.requireNonNull(feature, "feature")));
    }

    private boolean canEditSomeRestType(SecurityIdentity identity, Optional<String> feature) {
        for (PostTypeDefinition type : types.all()) {
            if (type.showInRest()
                    && feature.map(type::supports).orElse(true)
                    && canType(identity, "edit_posts", type.key())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean statusVisible(String status) {
        return statusVisible(acting.identity(), status);
    }

    @Override
    public boolean statusVisible(SecurityIdentity identity, String status) {
        Objects.requireNonNull(status, "status");
        Optional<PostStatusDefinition> found = statuses.find(status);
        if (found.isEmpty()) {
            return false;
        }
        if (found.get().publicly()) {
            return true;
        }
        if (found.get().internal() && !status.equals("trash")) {
            return false;
        }
        return canEditSomeRestType(identity);
    }

    @Override
    public List<String> restStatuses() {
        List<String> listed = new ArrayList<>();
        for (PostStatusDefinition status : statuses.all()) {
            if (!status.internal()) {
                listed.add(status.name());
            }
        }
        listed.add("trash");
        return List.copyOf(listed);
    }
}
