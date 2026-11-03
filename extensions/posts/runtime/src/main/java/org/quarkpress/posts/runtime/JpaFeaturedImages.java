package org.quarkpress.posts.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.LongNode;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.posts.FeaturedImages;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostSnapshot;

/**
 * The featured image on the {@code _thumbnail_id} meta. A write checks {@code edit_post} on the post it finds, takes
 * the site's post lock and the row lock, checks again and looks the attachment up under the lock; the value goes
 * through {@link ProtectedMeta}, so the post's modification date and version stay. Refusals come before any lock.
 */
@ApplicationScoped
public class JpaFeaturedImages implements FeaturedImages {

    /** The meta key. */
    public static final String THUMBNAIL_ID = "_thumbnail_id";

    @Inject
    SiteContext site;

    @Inject
    PostRepository posts;

    @Inject
    PostLocks siteLocks;

    @Inject
    Acting acting;

    @Inject
    PostCapabilityFacts facts;

    @Inject
    ProtectedMeta meta;

    /** The stored id cast to an integer as WordPress casts it; 0 when absent. */
    public static long thumbnailId(PostSnapshot post) {
        List<JsonNode> values = Objects.requireNonNull(post, "post").meta(THUMBNAIL_ID);
        return values.isEmpty() ? 0 : PhpInts.of(values.get(0));
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public void set(PostId post, long attachment) {
        Objects.requireNonNull(post, "post");
        SiteId siteId = site.siteId();
        PostEntity unlocked = posts.findPost(siteId, post).orElseThrow(() -> PostException.invalidPost(post));
        acting.require(siteId, "edit_post", Optional.of(facts.of(unlocked.toSnapshot())));
        siteLocks.lock(siteId);
        PostEntity row = posts.lockPost(siteId, post).orElseThrow(() -> PostException.invalidPost(post));
        acting.require(siteId, "edit_post", Optional.of(facts.of(row.toSnapshot())));
        if (attachment == 0) {
            meta.remove(siteId, row, THUMBNAIL_ID);
            return;
        }
        long id = Math.abs(attachment);
        if (id <= 0) {
            return;
        }
        Optional<PostEntity> target = posts.findPost(siteId, PostId.of(id));
        if (target.isEmpty()) {
            return;
        }
        if (target.get().type.equals("attachment")) {
            meta.put(siteId, row, THUMBNAIL_ID, LongNode.valueOf(id));
        } else {
            meta.remove(siteId, row, THUMBNAIL_ID);
        }
    }
}
