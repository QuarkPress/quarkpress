package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteContext;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Processing;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.runtime.PostEntity;
import org.quarkpress.posts.runtime.PostRepository;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.runtime.PhpValues;

/**
 * The attachment reads of one request, by id: the permission, the {@code Allow} and the handler share them. An
 * attachment post the media module holds no file for (one written through the posts module alone, or past it) reads
 * as WordPress reads an attachment without metadata: no type, no stored file, its guid as its file URL. The posts an
 * attachment names (its parent, its cover image) are read once per request too, however many attachments name them.
 */
@RequestScoped
public class MediaReads {

    final Map<Long, Optional<MediaSnapshot>> found = new HashMap<>();

    final Map<Long, Optional<PostSnapshot>> posts = new HashMap<>();

    @Inject
    Media media;

    @Inject
    PostRepository rows;

    @Inject
    SiteContext site;

    /**
     * The attachment of this site whose id is the request's {@code id}, with its media row, read without any
     * capability check and at most once per request; none for an id that is not positive, for any other post and for
     * another site's attachment. Throws while the site's media feature is off (through {@code Media.read}): callers
     * check the feature first.
     */
    Optional<MediaSnapshot> attachment(RestRequest request) {
        long id = request.param("id").map(PhpValues::toLong).orElse(0L);
        if (id <= 0) {
            return Optional.empty();
        }
        return attachment(PostId.of(id));
    }

    /**
     * The attachment of this site with this id, read as {@link #attachment(RestRequest)} reads the request's: without
     * a capability check, at most once per request, the media module's file or else the post alone.
     */
    Optional<MediaSnapshot> attachment(PostId id) {
        return found.computeIfAbsent(id.value(), key -> media.read(id).or(() -> withoutFile(id)));
    }

    /**
     * The post of this site with this id, whatever its type and status, read without a capability check and at most
     * once per request: the parents and cover images of the attachments a request shows; none for another site's.
     */
    Optional<PostSnapshot> post(PostId id) {
        return posts.computeIfAbsent(
                id.value(), key -> rows.findPost(site.siteId(), id).map(PostEntity::toSnapshot));
    }

    /** The attachment post of this site with this id, when the media module holds no file for it. */
    private Optional<MediaSnapshot> withoutFile(PostId id) {
        return rows.findPost(site.siteId(), id)
                .map(PostEntity::toSnapshot)
                .filter(post -> post.type().equals("attachment"))
                .map(MediaReads::fileless);
    }

    /**
     * An attachment post without a stored file: no type, storage key or attached file, no dimensions, sizes or
     * metadata, its stored alternative text (none when it has none), its guid as its URL.
     */
    static MediaSnapshot fileless(PostSnapshot post) {
        List<JsonNode> alt = post.meta("_wp_attachment_image_alt");
        return new MediaSnapshot(
                post,
                "",
                "",
                "",
                Optional.empty(),
                0,
                OptionalInt.empty(),
                OptionalInt.empty(),
                Map.of(),
                Optional.empty(),
                JsonNodeFactory.instance.objectNode(),
                alt.isEmpty() || !alt.get(0).isTextual() ? "" : alt.get(0).textValue(),
                Processing.NONE,
                post.guid(),
                Map.of());
    }

    /** The answer to a request whose attachment is not found: WordPress's invalid post id, 404. */
    static RestError invalidId() {
        return RestError.of("rest_post_invalid_id", "Invalid post ID.", 404);
    }

    /** Replaces the read of an attachment this request wrote, so that its later reads see the write. */
    void remember(MediaSnapshot attachment) {
        found.put(attachment.post().id().value(), Optional.of(attachment));
        posts.put(attachment.post().id().value(), Optional.of(attachment.post()));
    }

    /** Replaces the read of an attachment this request deleted: its later reads find none. */
    void forget(PostId attachment) {
        found.put(attachment.value(), Optional.empty());
        posts.put(attachment.value(), Optional.empty());
    }
}
