package org.quarkpress.media.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.arc.All;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.QuarkusTransactionException;
import io.quarkus.runtime.configuration.MemorySize;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.SystemException;
import jakarta.transaction.TransactionManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jboss.logging.Logger;
import org.quarkpress.core.Filters;
import org.quarkpress.core.QuarkPressClock;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.core.UserId;
import org.quarkpress.media.ImageInfo;
import org.quarkpress.media.ImageProcessor;
import org.quarkpress.media.KeyTakenException;
import org.quarkpress.media.MediaException;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.MediaStorage;
import org.quarkpress.media.MediaStored;
import org.quarkpress.media.MimeTypes;
import org.quarkpress.media.Upload;
import org.quarkpress.media.UploadCheck;
import org.quarkpress.media.UploadFilter;
import org.quarkpress.media.WriteMode;
import org.quarkpress.persistence.runtime.OptionTransactions;
import org.quarkpress.persistence.runtime.QuarkPressRuntimeConfig;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.posts.Posts;
import org.quarkpress.posts.runtime.Acting;
import org.quarkpress.posts.runtime.PostCapabilityFacts;
import org.quarkpress.posts.runtime.PostEntity;
import org.quarkpress.posts.runtime.PostRepository;
import org.quarkpress.security.runtime.InstallMode;

/**
 * Stores an upload as WordPress's REST upload does, in WordPress's order: the checks that come before the file is
 * read (the caller may upload and may name another author, the parent exists and the caller may edit it, an image the
 * processor cannot make sizes for, a parent that is an attachment or a revision), the folder's time, the size limit,
 * the network's upload limits on a network, the upload filters, the empty file, the type check, the folder and the
 * unique file name, then the file, the attachment post and its media row.
 * Every refusal comes before anything is written.
 *
 * <p>Only the checks that read rows and the rows themselves are done in a transaction: the file is checked, read and
 * stored between them, so that copying a large file never holds a database connection (unless the caller's own
 * transaction is open around the whole upload). The file is written create-exclusive: when another upload took the
 * name between the folder listing and the write, a new name is chosen from a fresh listing, a bounded number of
 * times. Once the rows' transaction holds the file, any failure marks it for rollback, and the file is removed when
 * it has rolled back.
 */
@ApplicationScoped
public class UploadPipeline {

    private static final Logger LOG = Logger.getLogger(UploadPipeline.class);

    /** How many names are tried when other uploads keep taking the chosen one. */
    static final int MAX_ATTEMPTS = 10;

    /** The length of the post's guid column, which holds the file's URL. */
    static final int MAX_GUID = 255;

    static final String CANNOT_EDIT = "Sorry, you are not allowed to upload media to this post.";
    static final String UNSUPPORTED_IMAGE = "The web server cannot generate responsive image sizes for this image."
            + " Convert it to JPEG or PNG before uploading.";
    static final String INVALID_PARENT_TYPE = "Invalid parent type.";
    static final String EMPTY_FILE = "File is empty. Please upload something more substantial.";
    static final String EMPTY_FILE_SINGLE_SITE = EMPTY_FILE
            + " This error could also be caused by uploads being disabled in your php.ini file or by post_max_size"
            + " being defined as smaller than upload_max_filesize in php.ini.";

    /** Declared image types WordPress lets through whatever the processor supports. */
    private static final Set<String> ALWAYS_ACCEPTED_IMAGES = Set.of("image/svg+xml", "image/heic", "image/heif");

    @Inject
    SiteContext site;

    @Inject
    Acting acting;

    @Inject
    PostRepository postRows;

    @Inject
    PostCapabilityFacts facts;

    @Inject
    Posts posts;

    @Inject
    PostTypes postTypes;

    @Inject
    MediaRepository mediaRows;

    @Inject
    ActiveStorage activeStorage;

    @Inject
    ImageProcessor processor;

    @Inject
    MediaConfig config;

    @Inject
    QuarkPressRuntimeConfig runtimeConfig;

    @Inject
    QuarkPressClock clock;

    @Inject
    InstallMode installMode;

    @Inject
    AllowedTypesFor allowedTypes;

    @Inject
    UploadLimits uploadLimits;

    @Inject
    MimeTypes mimeTypes;

    @Inject
    @All
    List<InstanceHandle<UploadFilter>> uploadFilters;

    @Inject
    StoredFiles storedFiles;

    @Inject
    OptionTransactions transactions;

    @Inject
    Event<MediaStored> storedEvents;

    @Inject
    TransactionManager transactionManager;

    /**
     * Stores {@code upload}; see {@link org.quarkpress.media.Media#store}. The checks that read rows come first, in the
     * caller's transaction or else in a short one of their own; the file is then checked, read and stored outside
     * any transaction of the upload's own, and only the attachment's rows are written in a transaction, which joins
     * the caller's when there is one. A refusal before the rows are written never marks the caller's transaction.
     */
    public MediaSnapshot store(Upload upload) {
        Objects.requireNonNull(upload, "upload");
        Checked checked = callerTransaction() ? check(upload) : checkInOwnTransaction(upload);
        FileWork file = storeFile(upload, checked);
        return writeRows(upload, checked, file);
    }

    /** {@link #check} in a short transaction of its own; an error it throws reaches the caller as itself. */
    private Checked checkInOwnTransaction(Upload upload) {
        try {
            return QuarkusTransaction.requiringNew().call(() -> check(upload));
        } catch (QuarkusTransactionException failure) {
            throw unwrapped(failure);
        }
    }

    /** The error the transaction runner wrapped, else {@code failure} itself. */
    private static RuntimeException unwrapped(QuarkusTransactionException failure) {
        if (failure.getCause() instanceof Error error) {
            throw error;
        }
        return failure;
    }

    /** What the checks that read rows found, for the file work and the rows that follow them. */
    private record Checked(
            SiteId siteId,
            Optional<UserId> user,
            Instant now,
            UploadFolders.Folder folder,
            long size,
            AllowedTypes allowed,
            SiteInfo siteInfo,
            String locale) {}

    /** The stored file and what was read from it. */
    private record FileWork(
            MediaStorage storage,
            Placed placed,
            String mimeType,
            Optional<ImageInfo> header,
            Optional<ObjectNode> imageMeta) {}

    /** Whether the caller has a transaction (one marked for rollback counts). */
    private boolean callerTransaction() {
        try {
            return transactionManager.getStatus() != Status.STATUS_NO_TRANSACTION;
        } catch (SystemException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /**
     * The refusals that come before the file is read, in WordPress's order, and what the later steps need from the
     * database: the folder, the size limits, the allowed types and the site's language.
     */
    private Checked check(Upload upload) {
        JpaMedia.requireFeature(site);
        SiteId siteId = site.siteId();
        acting.require(siteId, "upload_files", Optional.empty());
        // an author other than the caller needs the attachment type's edit-others capability, as any post does
        if (upload.author().isPresent() && !upload.author().equals(acting.user())) {
            acting.require(siteId, postTypes.capabilitiesOrPost("attachment").editOthersPosts(), Optional.empty());
        }
        Optional<PostSnapshot> parent = upload.parent()
                .map(id -> postRows.findPost(siteId, id)
                        .map(PostEntity::toSnapshot)
                        // WordPress answers a missing parent as its refused edit check
                        .orElseThrow(() -> new MediaException("rest_cannot_edit", CANNOT_EDIT)));
        if (parent.isPresent()) {
            acting.require(siteId, "edit_post", Optional.of(facts.of(parent.get())));
        }
        if (unsupportedImage(upload)) {
            throw new MediaException("rest_upload_image_type_not_supported", UNSUPPORTED_IMAGE);
        }
        if (parent.isPresent()
                && (parent.get().type().equals("attachment")
                        || parent.get().type().equals("revision"))) {
            throw new MediaException("rest_invalid_param", INVALID_PARENT_TYPE);
        }
        Instant now = clock.now();
        ZoneId zone = site.zone();
        UploadFolders.Folder folder = UploadFolders.of(
                siteId,
                runtimeConfig.mainSiteId(),
                site.options().flag("uploads_use_yearmonth_folders", true),
                UploadFolders.when(parent, zone, now));
        long size = measured(upload);
        Optional<MemorySize> limit = config.maxUploadSize();
        if (limit.isPresent() && size > limit.get().asLongValue()) {
            throw MediaException.upload("This file is too big. Files must be less than "
                    + limit.get().asLongValue() / 1024 + " KB in size.");
        }
        Optional<MediaException> overNetworkLimit = uploadLimits.forSite(size);
        if (overNetworkLimit.isPresent()) {
            throw overNetworkLimit.get();
        }
        return new Checked(siteId, acting.user(), now, folder, size, allowedTypes.current(), site.site(), locale());
    }

    /**
     * The upload filters, the empty file, the type check, the header and embedded data, then the file stored under a
     * unique name; nothing here touches the database.
     */
    private FileWork storeFile(Upload upload, Checked checked) {
        UploadCheck check = new UploadCheck(
                checked.siteId(),
                checked.user(),
                upload.clientName(),
                upload.declaredType(),
                checked.size(),
                upload.file());
        for (UploadFilter filter : Filters.ordered(uploadFilters)) {
            Optional<String> refusal = filter.refuse(check);
            if (refusal.isPresent()) {
                throw MediaException.upload(refusal.get());
            }
        }
        if (checked.size() <= 0) {
            throw MediaException.upload(installMode.multisite() ? EMPTY_FILE : EMPTY_FILE_SINGLE_SITE);
        }
        AllowedTypes allowed = checked.allowed();
        TypeCheck.TypeResult type;
        try {
            type = TypeCheck.check(upload.file(), upload.clientName(), mimeTypes.patterns(), allowed);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        if (type.refused()) {
            throw MediaException.upload(MediaException.TYPE_NOT_ALLOWED);
        }
        String mimeType = type.mimeType().orElseThrow();
        String sanitized = FileNames.sanitize(type.properName().orElse(upload.clientName()), allowed);
        Map<String, String> outputFormats = upload.convertFormat() ? config.effectiveOutputFormats() : Map.of();
        Optional<ImageInfo> header = header(upload.file());
        Optional<ObjectNode> imageMeta =
                header.map(info -> imageMeta(upload.file(), info.mimeType(), checked.locale()));
        MediaStorage storage = activeStorage.storage();
        Placed placed = place(
                storage,
                checked.siteInfo(),
                checked.folder(),
                sanitized,
                allowed,
                outputFormats,
                upload.file(),
                mimeType);
        return new FileWork(storage, placed, mimeType, header, imageMeta);
    }

    /**
     * Writes the attachment's rows in a transaction, the caller's when there is one. The stored file goes with that
     * transaction when it rolls back; when it cannot even begin, the file is removed at once. Any failure once the
     * transaction holds the file marks it for rollback (refusals of the posts module are {@link
     * IllegalArgumentException}s, which would otherwise commit).
     */
    private MediaSnapshot writeRows(Upload upload, Checked checked, FileWork file) {
        AtomicBoolean fileHandled = new AtomicBoolean();
        try {
            return QuarkusTransaction.joiningExisting().call(() -> {
                fileHandled.set(true);
                removeOnRollback(file.storage(), file.placed().key());
                try {
                    return record(upload, checked, file);
                } catch (RuntimeException | Error failure) {
                    markRollbackOnly(failure);
                    throw failure;
                }
            });
        } catch (RuntimeException failure) {
            if (!fileHandled.get()) {
                deleteQuietly(file, failure);
            }
            // the transaction runner wraps an error; the caller gets the error itself
            throw failure instanceof QuarkusTransactionException wrapped ? unwrapped(wrapped) : failure;
        }
    }

    private static void deleteQuietly(FileWork file, Throwable cause) {
        try {
            file.storage().delete(file.placed().key());
        } catch (RuntimeException deleteFailure) {
            cause.addSuppressed(deleteFailure);
        }
    }

    /**
     * Whether WordPress refuses the upload as an image no image editor can make sizes for; never for an upload without
     * the image type check.
     */
    private boolean unsupportedImage(Upload upload) {
        return upload.imageTypeCheck()
                && upload.declaredType().isPresent()
                && unsupportedImage(upload.declaredType().get(), upload.generateSubSizes());
    }

    /**
     * Whether an upload declaring {@code declared} is refused as an image no image editor can make sizes for: an
     * image type other than SVG and HEIC/HEIF that the processor does not support, when sizes are generated and
     * unprocessable images are not accepted.
     */
    public boolean unsupportedImage(String declared, boolean generateSubSizes) {
        return declared.startsWith("image/")
                && !ALWAYS_ACCEPTED_IMAGES.contains(declared)
                && generateSubSizes
                && !config.acceptUnprocessableImages()
                && !processor.supports(declared);
    }

    /** The stored file: its name in the folder, its key and its guid URL. */
    record Placed(String name, String key, String guid) {}

    /**
     * Stores the file under a unique name of the folder, never replacing a file: a name another upload took meanwhile
     * is chosen again from a fresh listing.
     */
    private Placed place(
            MediaStorage storage,
            SiteInfo siteInfo,
            UploadFolders.Folder folder,
            String sanitized,
            AllowedTypes allowed,
            Map<String, String> outputFormats,
            Path file,
            String mimeType) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            List<String> entries;
            try {
                entries = storage.list(folder.folderKey());
            } catch (UncheckedIOException | IllegalArgumentException failure) {
                LOG.debugf(failure, "the upload folder %s cannot be read", folder.folderKey());
                throw MediaException.upload("Unable to create directory " + folder.described()
                        + ". Is its parent directory writable by the server?");
            }
            String name =
                    UniqueNames.choose(sanitized, new ListedFolder(storage, folder, entries), allowed, outputFormats);
            String key = folder.key(name);
            String url;
            try {
                url = storage.guidUrl(siteInfo, key);
            } catch (IllegalArgumentException failure) {
                throw moveRefused(folder, failure);
            }
            if (url.codePointCount(0, url.length()) > MAX_GUID) {
                throw moveRefused(folder, null);
            }
            try {
                storage.put(key, file, mimeType, WriteMode.CREATE_NEW);
                return new Placed(name, key, url);
            } catch (KeyTakenException taken) {
                LOG.debugf("the upload name %s was taken meanwhile; choosing again", key);
            } catch (IOException | UncheckedIOException | IllegalArgumentException failure) {
                throw moveRefused(folder, failure);
            }
        }
        throw moveRefused(folder, null);
    }

    /** Ties the stored file to the transaction; when that cannot be done, the file is removed at once. */
    private void removeOnRollback(MediaStorage storage, String key) {
        try {
            storedFiles.removeOnRollback(key);
        } catch (RuntimeException failure) {
            try {
                storage.delete(key);
            } catch (RuntimeException deleteFailure) {
                failure.addSuppressed(deleteFailure);
            }
            throw failure;
        }
    }

    /** The upload's size, measured on its file, which must be the size it claims. */
    private static long measured(Upload upload) {
        long size;
        try {
            size = Files.size(upload.file());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        if (size != upload.size()) {
            throw new IllegalArgumentException(
                    "the upload claims " + upload.size() + " bytes but its file holds " + size);
        }
        return size;
    }

    private static MediaException moveRefused(UploadFolders.Folder folder, Exception cause) {
        if (cause != null) {
            LOG.debugf(cause, "an upload could not be stored in %s", folder.folderKey());
        }
        return MediaException.upload("The uploaded file could not be moved to " + folder.described() + ".");
    }

    /** Writes the attachment post and its media row for the stored file, then announces it. */
    private MediaSnapshot record(Upload upload, Checked checked, FileWork file) {
        SiteId siteId = checked.siteId();
        Placed placed = file.placed();
        Optional<ImageInfo> header = file.header();
        Optional<ObjectNode> imageMeta = file.imageMeta();
        EmbeddedText embedded = imageMeta.map(EmbeddedText::of).orElse(new EmbeddedText("", "", ""));
        // a name without a dot gives no title, so a sideload's title comes from the stored name
        String title = AttachmentFields.title(
                upload.title(), embedded, upload.clientNameTitle() ? upload.clientName() : "", placed.name());
        String excerpt = AttachmentFields.caption(upload.caption(), embedded);
        PostChanges.Builder changes = PostChanges.builder()
                .type("attachment")
                .title(title)
                .excerpt(excerpt)
                .content(upload.description().orElse(""))
                .guid(placed.guid());
        upload.status().ifPresent(changes::status);
        upload.author().ifPresent(changes::author);
        upload.parent().ifPresent(changes::parent);
        upload.date().ifPresent(changes::date);
        upload.dateGmt().ifPresent(changes::dateGmt);
        upload.slug().ifPresent(changes::slug);
        upload.commentStatus().ifPresent(changes::commentStatus);
        upload.pingStatus().ifPresent(changes::pingStatus);
        PostSnapshot post = posts.create(changes.build());

        MediaEntity row = MediaEntity.create(siteId, post.id());
        row.storageKey = placed.key();
        row.mimeType = file.mimeType();
        row.fileSize = upload.size();
        row.width = header.map(ImageInfo::width).orElse(null);
        row.height = header.map(ImageInfo::height).orElse(null);
        row.imageMeta = imageMeta.orElse(null);
        row.altText = AttachmentFields.alt(upload.altText(), embedded);
        row.processing = MediaEntity.PENDING;
        row.storedBytes = upload.size();
        mediaRows.persist(row);
        mediaRows.flush();

        MediaSnapshot snapshot =
                JpaMedia.snapshot(post, row, file.storage(), checked.siteInfo(), runtimeConfig.mainSiteId());
        transactions.publish(storedEvents, new MediaStored(siteId, checked.now(), snapshot));
        return snapshot;
    }

    /** The image's stored dimensions read from its header; empty for anything else or a header a parser chokes on. */
    private static Optional<ImageInfo> header(Path file) {
        try {
            return ImageHeaders.read(file);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } catch (StackOverflowError | OutOfMemoryError failure) {
            LOG.warnf("the image header of an upload could not be read (%s); it is stored without dimensions", failure);
            return Optional.empty();
        }
    }

    /** The image's embedded data, its texts read for the site's language {@code locale}. */
    private static ObjectNode imageMeta(Path file, String imageType, String locale) {
        return readImageMeta(() -> ImageMetaReader.read(file, imageType, locale));
    }

    /** A read of an image's embedded data. */
    @FunctionalInterface
    interface MetaRead {
        ObjectNode read() throws IOException;
    }

    /**
     * The embedded data {@code read} gives. When a parser library fails with a stack overflow or runs out of memory,
     * the failure is logged and WordPress's thirteen keys with their empty defaults are kept instead, as for an image
     * without embedded data.
     */
    static ObjectNode readImageMeta(MetaRead read) {
        try {
            return read.read();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } catch (StackOverflowError | OutOfMemoryError failure) {
            LOG.warnf("the embedded data of an upload could not be read (%s); it is stored without", failure);
            return new ImageMetaReader.Fields().toJson("");
        }
    }

    /** The site's locale in WordPress's form; {@code en_US} when the site names none. */
    private String locale() {
        String locale = site.options().string("WPLANG", "");
        return locale.isEmpty() ? "en_US" : locale;
    }

    private void markRollbackOnly(Throwable failure) {
        try {
            transactionManager.setRollbackOnly();
        } catch (SystemException | IllegalStateException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    /** The upload folder as the listing taken for one choice of name shows it, checked again in the storage. */
    private static final class ListedFolder implements UploadFolder {

        private final MediaStorage storage;
        private final UploadFolders.Folder folder;
        private final List<String> entries;
        private final Set<String> names;

        ListedFolder(MediaStorage storage, UploadFolders.Folder folder, List<String> entries) {
            this.storage = storage;
            this.folder = folder;
            this.entries = List.copyOf(entries);
            this.names = new HashSet<>(entries);
        }

        @Override
        public boolean exists(String name) {
            if (names.contains(name)) {
                return true;
            }
            try {
                return storage.exists(folder.key(name));
            } catch (IllegalArgumentException invalid) {
                // no file can be stored under a key the storage refuses
                return false;
            }
        }

        @Override
        public List<String> entries() {
            return entries;
        }
    }
}
