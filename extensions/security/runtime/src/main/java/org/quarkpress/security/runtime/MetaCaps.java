package org.quarkpress.security.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.quarkpress.core.Filters;
import org.quarkpress.security.CapObject;
import org.quarkpress.security.MetaCapRequest;
import org.quarkpress.security.MetaCapabilityFilter;
import org.quarkpress.security.MetaCapabilityNames;
import org.quarkpress.security.PostFacts;
import org.quarkpress.security.PostTypeCapabilities;
import org.quarkpress.security.PostVisibility;

/**
 * Maps a capability to the primitive capabilities a user must hold, as WordPress's {@code map_meta_cap} does. Meta
 * capabilities about an object (a post, a term, a user) look at that object; capabilities WordPress restricts in a
 * network depend on whether the user is a network administrator; names without a rule map to themselves. The answer
 * {@link #DO_NOT_ALLOW} is never held by anyone. {@link MetaCapabilityFilter}s run after every step.
 */
public final class MetaCaps {

    /** Required when a check must fail for everyone, network administrators included. */
    public static final String DO_NOT_ALLOW = "do_not_allow";

    /** Held by everyone, logged out or not. */
    public static final String EXIST = "exist";

    static final List<String> DENY = List.of(DO_NOT_ALLOW);

    private static final Set<String> CORE_META = Set.of("edit_post", "read_post", "delete_post");

    private static final Map<String, String> BLOCK_CAPS = Map.of(
            "edit_blocks", "edit_posts",
            "edit_others_blocks", "edit_others_posts",
            "publish_blocks", "publish_posts",
            "read_private_blocks", "read_private_posts",
            "delete_blocks", "delete_posts",
            "delete_private_blocks", "delete_private_posts",
            "delete_published_blocks", "delete_published_posts",
            "delete_others_blocks", "delete_others_posts",
            "edit_private_blocks", "edit_private_posts",
            "edit_published_blocks", "edit_published_posts");

    private final List<MetaCapabilityFilter> filters;
    private final Map<String, String> metaNames;

    /** {@code filters} in the order they run (use {@code Filters.ordered}). */
    public MetaCaps(List<MetaCapabilityFilter> filters, List<MetaCapabilityNames> names) {
        this.filters = List.copyOf(filters);
        Map<String, String> merged = new HashMap<>();
        for (MetaCapabilityNames source : names) {
            for (Map.Entry<String, String> name : source.metaCapabilities().entrySet()) {
                String existing = merged.get(name.getKey());
                if (existing != null && !existing.equals(name.getValue())) {
                    throw new IllegalArgumentException("the meta capability " + name.getKey() + " is claimed for both "
                            + existing + " and " + name.getValue());
                }
                if (!CORE_META.contains(name.getValue())) {
                    throw new IllegalArgumentException("the meta capability " + name.getKey()
                            + " must stand for edit_post, read_post or delete_post, not " + name.getValue());
                }
                merged.putIfAbsent(name.getKey(), name.getValue());
            }
        }
        this.metaNames = Map.copyOf(merged);
    }

    /** The primitive capabilities {@code capability} requires, in order, without duplicates. */
    public Set<String> map(String capability, Optional<CapObject> object, MapContext context) {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(context, "context");
        List<String> required = explicit(capability, object, context);
        if (required == null) {
            String core = metaNames.get(capability);
            if (core != null) {
                return map(core, object, context);
            }
            required = List.of(BLOCK_CAPS.getOrDefault(capability, capability));
        }
        MetaCapRequest request =
                new MetaCapRequest(filterName(capability, object, context), context.user(), context.site(), object);
        Set<String> mapped = Filters.apply(
                filters,
                Collections.unmodifiableSet(new LinkedHashSet<>(required)),
                (filter, caps) -> filter.apply(caps, request));
        return Collections.unmodifiableSet(new LinkedHashSet<>(mapped));
    }

    /**
     * The name filters see: as WordPress does for a post of a type that does not map meta capabilities, a post form
     * is replaced by the type's own name.
     */
    private static String filterName(String capability, Optional<CapObject> object, MapContext context) {
        if (CORE_META.contains(capability)
                && object.isPresent()
                && object.get() instanceof PostFacts post
                && post.type().isPresent()
                && !post.type().get().mapMetaCap()
                && !(capability.equals("delete_post")
                        && (sameId(context, "page_for_posts", post) || sameId(context, "page_on_front", post)))) {
            return post.type().get().metaName(capability);
        }
        return capability;
    }

    /** The rule for {@code capability}, or null when it has none of its own. */
    private List<String> explicit(String capability, Optional<CapObject> object, MapContext context) {
        return switch (capability) {
            case "edit_post", "edit_page", "delete_post", "delete_page", "read_post", "read_page", "publish_post" ->
                postRule(capability, object, context);
            default -> otherRule(capability, object, context);
        };
    }

    /**
     * Rules for the other named capabilities, or null for a name without a rule. The privacy capabilities are needed
     * by the privacy-page rule; the user, term and network rules are added next to them.
     */
    private List<String> otherRule(String capability, Optional<CapObject> object, MapContext context) {
        return switch (capability) {
            case "export_others_personal_data", "erase_others_personal_data", "manage_privacy_options" ->
                List.of(context.multisite() ? "manage_network" : "manage_options");
            default -> null;
        };
    }

    private List<String> postRule(String capability, Optional<CapObject> object, MapContext context) {
        if (object.isEmpty() || !(object.get() instanceof PostFacts post)) {
            return DENY;
        }
        String kind = capability.substring(0, capability.indexOf('_'));
        if (kind.equals("delete")
                && (sameId(context, "page_for_posts", post) || sameId(context, "page_on_front", post))) {
            return List.of("manage_options");
        }
        if (post.type().isEmpty()) {
            return List.of("edit_others_posts");
        }
        PostTypeCapabilities type = post.type().get();
        if (capability.equals("publish_post")) {
            return List.of(type.publishPosts());
        }
        if (!type.mapMetaCap()) {
            return List.of(type.metaName(capability));
        }
        boolean own = post.author().isPresent() && post.author().equals(context.user());
        List<String> caps = new ArrayList<>();
        switch (kind) {
            case "edit" -> {
                if (own) {
                    caps.add(published(post) ? type.editPublishedPosts() : type.editPosts());
                } else {
                    caps.add(type.editOthersPosts());
                    if (isPublishedStatus(post.status())) {
                        caps.add(type.editPublishedPosts());
                    } else if (post.status().equals("private")) {
                        caps.add(type.editPrivatePosts());
                    }
                }
                addPrivacy(caps, post, context);
            }
            case "delete" -> {
                if (own) {
                    caps.add(published(post) ? type.deletePublishedPosts() : type.deletePosts());
                } else {
                    caps.add(type.deleteOthersPosts());
                    if (isPublishedStatus(post.status())) {
                        caps.add(type.deletePublishedPosts());
                    } else if (post.status().equals("private")) {
                        caps.add(type.deletePrivatePosts());
                    }
                }
                addPrivacy(caps, post, context);
            }
            default -> {
                PostVisibility visibility = post.effectiveVisibility();
                if (visibility == PostVisibility.UNREGISTERED) {
                    caps.add("edit_others_posts");
                } else if (visibility == PostVisibility.PUBLIC || own) {
                    caps.add(type.read());
                } else if (visibility == PostVisibility.PRIVATE) {
                    caps.add(type.readPrivatePosts());
                } else {
                    caps.addAll(map("edit_post", Optional.of(post), context));
                }
            }
        }
        return caps;
    }

    /** For the author's own post: published or scheduled, or trashed from one of those. */
    private static boolean published(PostFacts post) {
        if (post.status().equals("trash")) {
            return post.statusBeforeTrash().map(MetaCaps::isPublishedStatus).orElse(false);
        }
        return isPublishedStatus(post.status());
    }

    private static boolean isPublishedStatus(String status) {
        return status.equals("publish") || status.equals("future");
    }

    private void addPrivacy(List<String> caps, PostFacts post, MapContext context) {
        if (sameId(context, "wp_page_for_privacy_policy", post)) {
            caps.addAll(map("manage_privacy_options", Optional.empty(), context));
        }
    }

    /** Whether the site option {@code name}, read as PHP reads an integer, is the post's id. */
    static boolean sameId(MapContext context, String name, PostFacts post) {
        return context.siteOption(name).map(PhpTruth::intValue).orElse(0L)
                == post.id().value();
    }
}
