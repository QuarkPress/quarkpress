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
import org.quarkpress.posts.AttachmentThumbnails;
import org.quarkpress.posts.FeaturedImages;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostSnapshot;

/**
 * The featured image on the {@code _thumbnail_id} meta. A write checks {@code edit_post} on the post it finds, takes
 * the site's post lock and the row lock, checks again and looks the attachment up under the lock; the value goes
 * through {@link ProtectedMeta}, so the post's modification date and version stay. Whether an attachment can be the
 * featured image is asked of {@link AttachmentThumbnails} under the locks. Refusals come before any lock.
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

    @Inject
    FeaturedImageReferences references;

    @Inject
    AttachmentThumbnails thumbnails;

    /** The stored id cast to an integer as WordPress casts it; 0 when absent. */
    public static long thumbnailId(PostSnapshot post) {
        List<JsonNode> values = Objects.requireNonNull(post, "post").meta(THUMBNAIL_ID);
        return values.isEmpty() ? 0 : PhpInts.of(values.get(0));
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public boolean set(PostId post, long attachment) {
        Objects.requireNonNull(post, "post");
        SiteId siteId = site.siteId();
        PostEntity unlocked = posts.findPost(siteId, post).orElseThrow(() -> PostException.invalidPost(post));
        acting.require(siteId, "edit_post", Optional.of(facts.of(unlocked.toSnapshot())));
        siteLocks.lock(siteId);
        PostEntity row = posts.lockPost(siteId, post).orElseThrow(() -> PostException.invalidPost(post));
        acting.require(siteId, "edit_post", Optional.of(facts.of(row.toSnapshot())));
        if (attachment == 0) {
            return remove(siteId, row);
        }
        long id = Math.abs(attachment);
        if (id <= 0) {
            return false;
        }
        Optional<PostEntity> target = posts.findPost(siteId, PostId.of(id));
        if (target.isEmpty()) {
            return false;
        }
        if (!target.get().type.equals("attachment") || !thumbnails.canBeThumbnail(siteId, PostId.of(id))) {
            return remove(siteId, row);
        }
        List<JsonNode> stored = MetaRows.values(row.meta, THUMBNAIL_ID);
        if (stored.size() == 1 && storesId(stored.get(0), id)) {
            return false;
        }
        meta.put(siteId, row, THUMBNAIL_ID, LongNode.valueOf(id));
        return true;
    }

    /**
     * Whether a stored value is {@code id} already: the whole number, or its decimal text (WordPress keeps meta as
     * text, so an imported value is one).
     */
    private static boolean storesId(JsonNode value, long id) {
        if (value.isTextual()) {
            return value.textValue().equals(Long.toString(id));
        }
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() == id;
    }

    /** Removes the featured image of the locked row; whether there was one to remove. */
    private boolean remove(SiteId siteId, PostEntity row) {
        if (row.meta == null || !row.meta.has(THUMBNAIL_ID)) {
            return false;
        }
        meta.remove(siteId, row, THUMBNAIL_ID);
        return true;
    }

    /**
     * Removes from every post of {@code site} the {@code _thumbnail_id} values naming {@code attachment}, as WordPress
     * deletes the meta rows holding its id when it deletes an attachment: a value naming it is its id as a whole number
     * or as its decimal text; the post's other values stay, and the key goes once none is left. The posts are locked
     * in id order and their values read again under the lock; the meta alone is written, so their modification dates
     * and versions stay. The caller holds the site's post lock, in a transaction; no capability is checked.
     */
    public void forget(SiteId site, PostId attachment) {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(attachment, "attachment");
        String id = Long.toString(attachment.value());
        for (long candidate : references.postsNaming(site, attachment.value())) {
            posts.lockPost(site, PostId.of(candidate)).ifPresent(row -> {
                List<JsonNode> values = MetaRows.values(row.meta, THUMBNAIL_ID);
                List<JsonNode> kept =
                        values.stream().filter(value -> !names(value, id)).toList();
                if (kept.size() != values.size()) {
                    meta.set(site, row, THUMBNAIL_ID, kept);
                }
            });
        }
    }

    /** Whether a stored value is {@code id}, as a whole number or as that text. */
    private static boolean names(JsonNode value, String id) {
        return value.isTextual()
                ? value.textValue().equals(id)
                : value.isIntegralNumber() && value.asText().equals(id);
    }
}
