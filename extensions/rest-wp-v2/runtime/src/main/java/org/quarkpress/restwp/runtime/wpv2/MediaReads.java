package org.quarkpress.restwp.runtime.wpv2;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.core.PostId;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.runtime.PhpValues;

/** The attachment reads of one request, by id: the permission, the {@code Allow} and the handler share them. */
@RequestScoped
public class MediaReads {

    final Map<Long, Optional<MediaSnapshot>> found = new HashMap<>();

    @Inject
    Media media;

    /**
     * The attachment of this site whose id is the request's {@code id}, with its media row, read without any
     * capability check and at most once per request; none for an id that is not positive, for any other post and for
     * another site's attachment.
     */
    Optional<MediaSnapshot> attachment(RestRequest request) {
        long id = request.param("id").map(PhpValues::toLong).orElse(0L);
        if (id <= 0) {
            return Optional.empty();
        }
        return found.computeIfAbsent(id, key -> media.read(PostId.of(key)));
    }

    /** Replaces the read of an attachment this request wrote, so that its later reads see the write. */
    void remember(MediaSnapshot attachment) {
        found.put(attachment.post().id().value(), Optional.of(attachment));
    }

    /** Replaces the read of an attachment this request deleted: its later reads find none. */
    void forget(PostId attachment) {
        found.put(attachment.value(), Optional.empty());
    }
}
