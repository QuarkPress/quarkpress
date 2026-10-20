package org.quarkpress.posts.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.function.Function;
import org.quarkpress.core.PostId;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostStatuses;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.security.PostFacts;
import org.quarkpress.security.PostTypeCapabilities;

/**
 * What security's capability checks need to know about a post. Call it while bound to the post's site: an
 * attachment's parent is read there.
 */
@ApplicationScoped
public class PostCapabilityFacts {

    /** Parent chains of attachments are followed at most this deep (WordPress follows them through inherit). */
    private static final int MAX_PARENT_DEPTH = 32;

    @Inject
    PostTypes types;

    @Inject
    PostStatuses statuses;

    @Inject
    PostRepository posts;

    public PostFacts of(PostSnapshot post) {
        return facts(
                post, types, statuses, id -> posts.findPost(post.site(), id).map(PostEntity::toSnapshot));
    }

    /**
     * The facts of {@code post}: its type's capability names ({@code post}'s, marked unregistered, for a type that
     * is not registered), its status with the registry's visibility, its author, its status before trashing (absent
     * when the recorded one is empty or "0"), and for an attachment the status it inherits from its parent.
     */
    public static PostFacts facts(
            PostSnapshot post,
            PostTypes types,
            PostStatuses statuses,
            Function<PostId, Optional<PostSnapshot>> parents) {
        return facts(post, types, statuses, parents, 0);
    }

    private static PostFacts facts(
            PostSnapshot post,
            PostTypes types,
            PostStatuses statuses,
            Function<PostId, Optional<PostSnapshot>> parents,
            int depth) {
        PostFacts facts;
        if (post.type().equals("attachment")) {
            Optional<PostFacts> parent = Optional.empty();
            if (post.status().equals("inherit")
                    && depth < MAX_PARENT_DEPTH
                    && post.parent().isPresent()
                    && !post.parent().get().equals(post.id())) {
                parent = parents.apply(post.parent().get())
                        .map(found -> facts(found, types, statuses, parents, depth + 1));
            }
            facts = PostFacts.ofAttachment(post.id(), post.status(), post.author(), parent);
            if (parent.isPresent() && parent.get().effectiveStatus().equals("trash")) {
                String before = parent.get().statusBeforeTrash().orElse("publish");
                facts = facts.withEffectiveStatus(before, statuses.visibility(before));
            }
        } else {
            Optional<PostTypeCapabilities> names = types.capabilities(post.type());
            facts = PostFacts.of(post.id(), names.orElse(PostTypeCapabilities.POST), post.status(), post.author())
                    .withEffectiveStatus(post.status(), statuses.visibility(post.status()));
            if (names.isEmpty()) {
                facts = facts.withUnregisteredType();
            }
        }
        if (post.status().equals("trash")) {
            Optional<String> before = post.metaText("_wp_trash_meta_status").filter(text -> !PostRules.phpEmpty(text));
            if (before.isPresent()) {
                facts = facts.withStatusBeforeTrash(before.get());
            }
        }
        return facts;
    }
}
