package org.quarkpress.media;

import java.util.List;
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

    /**
     * Stores an upload as {@link #store} does, in a transaction of its own that commits, then makes its generated
     * files as WordPress's server path does: a scaled, converted or rotated main file when one applies, then each
     * registered size, the metadata saved first and again after each size. In the {@code sync} processing mode the
     * answer is the processed attachment; in the {@code async} mode it is the stored one, still
     * {@link Processing#PENDING}, processed in the background. A file that cannot be processed never fails the upload:
     * a size that cannot be made is left out and the attachment is {@link Processing#PARTIAL}. {@link MediaProcessed}
     * announces the end of the processing.
     *
     * @throws IllegalStateException when a transaction is active: processing commits each save on its own
     */
    MediaSnapshot upload(Upload upload);

    /**
     * WordPress's post-process of an attachment: one still {@link Processing#PENDING} is processed from scratch;
     * otherwise its {@linkplain #missingSizes missing sizes} are made from the original file. With nothing missing,
     * or nothing the image processor can make, the attachment is returned unchanged and nothing is written. Needs
     * {@code edit_post} on the attachment.
     *
     * @throws IllegalStateException when a transaction is active
     * @throws org.quarkpress.posts.PostException when {@code attachment} is not an attachment of the site
     */
    MediaSnapshot process(PostId attachment);

    /**
     * The registered sizes the attachment lacks, as WordPress lists them for its REST view, in registration order:
     * every size while the upload is pending; for an image the sizes possible for the original's stored dimensions
     * (never rotated) that are not present by name; none for anything else.
     *
     * @throws org.quarkpress.posts.PostException when {@code attachment} is not an attachment of the site
     */
    List<String> missingSizes(PostId attachment);

    /** The attachment with this id on the current site, as {@code Posts.find} reads it; empty for any other post. */
    Optional<MediaSnapshot> find(PostId attachment);
}
