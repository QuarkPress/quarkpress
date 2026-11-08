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
     * WordPress's post-process of an attachment: one without metadata, still {@link Processing#PENDING} or {@link
     * Processing#FAILED} (no run saved its metadata), is processed from scratch, as WordPress does; otherwise its
     * {@linkplain #missingSizes missing sizes} are made from the original file. With nothing missing, or nothing the
     * image processor can make, the attachment is returned unchanged and nothing is written. Needs {@code edit_post}
     * on the attachment.
     *
     * @throws IllegalStateException when a transaction is active
     * @throws org.quarkpress.posts.PostException when {@code attachment} is not an attachment of the site
     */
    MediaSnapshot process(PostId attachment);

    /**
     * The registered sizes the attachment lacks, as WordPress lists them for its REST view, in registration order:
     * every size while it has no metadata (pending or failed), whatever its dimensions; for an image the sizes possible
     * for the original's stored dimensions (never rotated) that are not present by name; none for anything else.
     *
     * @throws org.quarkpress.posts.PostException when {@code attachment} is not an attachment of the site
     */
    List<String> missingSizes(PostId attachment);

    /** The attachment with this id on the current site, as {@code Posts.find} reads it; empty for any other post. */
    Optional<MediaSnapshot> find(PostId attachment);

    /**
     * Updates an attachment as WordPress's REST update does, in one transaction (joining the caller's): the title,
     * caption and description, the parent, the author and the status are saved by {@code Posts.update} with its
     * capability, text, slug and status rules; the alternative text is cleaned as a text field and written to the
     * media row. Needs {@code edit_post} on the attachment. The attachment post is saved even when only the
     * alternative text changes, as WordPress saves it: its modification date and version move and the save is
     * announced as an attachment edit. A parent that is an attachment or a revision is refused with
     * {@link MediaException} {@code rest_invalid_param} before anything is written; 0 detaches the attachment.
     *
     * @throws org.quarkpress.posts.PostException when {@code attachment} is not an attachment of the site
     * @throws jakarta.persistence.OptimisticLockException when the attachment changed since
     *     {@link MediaChanges#expectedVersion}
     */
    MediaSnapshot update(PostId attachment, MediaChanges changes);

    /**
     * Deletes an attachment for good, as WordPress's {@code wp_delete_attachment} does with media trash off (its
     * default): through {@code Posts.delete} with {@code force}, so it needs {@code delete_post} on the attachment, in
     * one transaction (joining the caller's). The featured images that name it are cleared and the media row goes with
     * the post; once the delete has committed, every file of it is removed (the sizes, the untouched original, the
     * editor's backups and the main file last). A file already missing is skipped and a storage failure is logged,
     * never raised; a delete that rolls back keeps every file. Deleting the attachment through {@code Posts.delete}
     * removes its files alike.
     *
     * @return the attachment as it was; empty when {@code attachment} is no attachment of the site
     */
    Optional<MediaSnapshot> delete(PostId attachment);

    /**
     * The attachments of the current site matching {@code query}, read as {@code Posts.query} reads a post query of
     * type {@code attachment} (its capability and read rules included), each with its media row. An attachment
     * without a media row is not media: it is neither listed nor counted.
     */
    MediaPage query(MediaQuery query);
}
