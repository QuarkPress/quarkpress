package org.quarkpress.media.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.posts.AttachmentThumbnails;

/**
 * Tells posts which attachments can be featured images: those whose stored file is an image of any type (SVG, HEIC
 * and TIFF included). Audio, video and documents cannot (no previews are made of PDFs), nor can an attachment without
 * a stored file.
 */
@ApplicationScoped
public class MediaThumbnails implements AttachmentThumbnails {

    @Inject
    MediaRepository media;

    @Override
    @Transactional
    public boolean canBeThumbnail(SiteId site, PostId attachment) {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(attachment, "attachment");
        Optional<MediaEntity> row = media.findMedia(site, attachment);
        return row.isPresent()
                && row.get().storageKey != null
                && !row.get().storageKey.isEmpty()
                && row.get().mimeType != null
                && row.get().mimeType.startsWith("image/");
    }
}
