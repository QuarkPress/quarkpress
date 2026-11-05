package org.quarkpress.media;

import java.util.Optional;
import org.quarkpress.core.PostId;

/**
 * The media library of the current site. Every method refuses with {@code SiteFeatureDisabledException} when the
 * site's {@code media} feature is off; refusals of an upload are {@link MediaException}s, capability refusals Quarkus's
 * {@code UnauthorizedException} and {@code ForbiddenException}, all before anything is stored.
 */
public interface Media {

    /**
     * Stores an upload as WordPress's REST upload does: the checks of the caller and the parent, the type check
     * against the name and the content, the file name and folder, the attachment post and its media row, with the
     * generated sizes still to be made ({@link Processing#PENDING}). Joins the caller's transaction when there is
     * one; when it fails after the file was stored, the transaction is marked for rollback and the file is removed
     * once it has rolled back. {@link MediaStored} announces the stored upload.
     */
    MediaSnapshot store(Upload upload);

    /** The attachment with this id on the current site, as {@code Posts.find} reads it; empty for any other post. */
    Optional<MediaSnapshot> find(PostId attachment);
}
