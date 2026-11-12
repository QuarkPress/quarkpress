package org.quarkpress.media;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
     * A step run between storing an upload and processing it, such as the fields a REST request sets on the new
     * attachment.
     */
    @FunctionalInterface
    interface AfterStore {

        /** Runs with the stored attachment, still {@link Processing#PENDING}, outside any transaction. */
        void stored(MediaSnapshot stored);
    }

    /**
     * Stores an upload as {@link #upload(Upload)} does, in a committed transaction of its own, then runs {@code
     * afterStore} with the stored, still pending attachment outside any transaction, then processes it as {@link
     * #upload(Upload)} does (in the {@code async} mode, the background run is queued only once {@code afterStore}
     * returned). The answer is the attachment as it is after {@code afterStore}, so it shows what the step wrote: the
     * processed attachment, or in the {@code async} mode the stored one read again. An exception thrown by {@code
     * afterStore} reaches the caller unchanged; the attachment then stays stored and unprocessed ({@link
     * Processing#PENDING}, without metadata), in either mode.
     *
     * @throws IllegalStateException when a transaction is active: processing commits each save on its own
     */
    MediaSnapshot upload(Upload upload, AfterStore afterStore);

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
     * The attachment with this id on the current site with its media row, as stored, whoever asks: no capability is
     * checked, as WordPress's own lookup checks none. For callers that decide access themselves. Empty for any other
     * post, and for an attachment without a media row.
     */
    Optional<MediaSnapshot> read(PostId attachment);

    /**
     * Copies {@code body} to a new file in the active storage's temporary area ({@link MediaStorage#temporaryFile})
     * and answers that file, which the caller deletes once done with it. When the copy fails, the file is deleted and
     * the failure thrown. The stream is read to its end, not closed.
     */
    Path spool(InputStream body) throws IOException;

    /**
     * The largest upload the site tells its editor it takes, as WordPress's {@code wp_max_upload_size} does: the
     * smaller of {@code quarkpress.media.max-upload-size} (when set) and the HTTP body limit ({@code
     * quarkus.http.limits.max-body-size}, which plays the part of PHP's {@code post_max_size} and {@code
     * upload_max_filesize}), and on a network also the per-file limit {@code fileupload_maxk} in KB (whether or not
     * the network's upload checks are on) and, with the checks on, the space left on the site. Checks no capability:
     * the caller decides what it tells whom.
     */
    long maxUploadSize();

    /**
     * The file types the caller may upload on the current site, as extension patterns (such as {@code jpg|jpeg|jpe})
     * with their MIME types, in lookup order: the site's list, with the network's list and the caller's {@code
     * unfiltered_html} applied, as WordPress's {@code get_allowed_mime_types()} answers.
     */
    List<Map.Entry<String, String>> allowedTypes();

    /**
     * The type generated sizes of an image of {@code mimeType} are written in, when the image processor supports that
     * type and the configured output formats map it to another type the processor writes; empty otherwise.
     */
    Optional<String> outputType(String mimeType);

    /**
     * Whether an upload declaring {@code declaredType} is refused as an image the server cannot make sizes for,
     * before its file is read: an image type the processor does not support (SVG, HEIC and HEIF excepted), when sizes
     * are generated ({@code generateSubSizes}) and unprocessable images are not accepted.
     */
    boolean refusesImageType(String declaredType, boolean generateSubSizes);

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
     * type {@code attachment} (its capability and read rules included, unless the query is {@linkplain
     * MediaQuery#unfiltered() unfiltered}), each with its media row (none at all for a {@linkplain
     * MediaQuery#countOnly() count-only} query). An attachment without a media row is not media: it is neither listed
     * nor counted.
     */
    MediaPage query(MediaQuery query);
}
