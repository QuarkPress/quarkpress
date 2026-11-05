package org.quarkpress.media.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.arc.All;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.runtime.configuration.MemorySize;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.SystemException;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.Transactional;
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
import org.jboss.logging.Logger;
import org.quarkpress.core.Filters;
import org.quarkpress.core.QuarkPressClock;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteInfo;
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
 * the upload filters, the empty file, the type check, the folder and the unique file name, then the file, the
 * attachment post and its media row.
 * Every refusal comes before anything is written.
 *
 * <p>The file is written create-exclusive: when another upload took the name between the folder listing and the
 * write, a new name is chosen from a fresh listing, a bounded number of times. Once the file is stored, any failure
 * marks the transaction for rollback (refusals of the posts module are {@link IllegalArgumentException}s, which would
 * otherwise commit), and the file is removed when the transaction has rolled back.
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

    /** Stores {@code upload}; see {@link org.quarkpress.media.Media#store}. */
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public MediaSnapshot store(Upload upload) {
        Objects.requireNonNull(upload, "upload");
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
        UploadCheck check =
                new UploadCheck(siteId, acting.user(), upload.clientName(), upload.declaredType(), size, upload.file());
        for (UploadFilter filter : Filters.ordered(uploadFilters)) {
            Optional<String> refusal = filter.refuse(check);
            if (refusal.isPresent()) {
                throw MediaException.upload(refusal.get());
            }
        }
        if (size <= 0) {
            throw MediaException.upload(installMode.multisite() ? EMPTY_FILE : EMPTY_FILE_SINGLE_SITE);
        }
        AllowedTypes allowed = allowedTypes.current();
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
        MediaStorage storage = activeStorage.storage();
        SiteInfo siteInfo = site.site();
        Placed placed = place(storage, siteInfo, folder, sanitized, allowed, outputFormats, upload.file(), mimeType);
        try {
            return record(upload, siteId, siteInfo, storage, placed, mimeType, now);
        } catch (RuntimeException | Error failure) {
            markRollbackOnly(failure);
            throw failure;
        }
    }

    /** Whether WordPress refuses the upload as an image no image editor can make sizes for. */
    private boolean unsupportedImage(Upload upload) {
        if (upload.declaredType().isEmpty()) {
            return false;
        }
        String declared = upload.declaredType().get();
        return declared.startsWith("image/")
                && !ALWAYS_ACCEPTED_IMAGES.contains(declared)
                && upload.generateSubSizes()
                && !config.acceptUnprocessableImages()
                && !processor.supports(declared);
    }

    /** The stored file: its name in the folder, its key and its URL. */
    record Placed(String name, String key, String url) {}

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
                url = storage.publicUrl(siteInfo, key);
            } catch (IllegalArgumentException failure) {
                throw moveRefused(folder, failure);
            }
            if (url.codePointCount(0, url.length()) > MAX_GUID) {
                throw moveRefused(folder, null);
            }
            try {
                storage.put(key, file, mimeType, WriteMode.CREATE_NEW);
                removeOnRollback(storage, key);
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
    private MediaSnapshot record(
            Upload upload,
            SiteId siteId,
            SiteInfo siteInfo,
            MediaStorage storage,
            Placed placed,
            String mimeType,
            Instant now) {
        Optional<ImageInfo> header = header(upload.file());
        Optional<ObjectNode> imageMeta = header.map(info -> imageMeta(upload.file(), info.mimeType()));
        EmbeddedText embedded = imageMeta.map(EmbeddedText::of).orElse(new EmbeddedText("", "", ""));
        String title = AttachmentFields.title(upload.title(), embedded, upload.clientName(), placed.name());
        String excerpt = AttachmentFields.caption(upload.caption(), embedded);
        PostChanges.Builder changes = PostChanges.builder()
                .type("attachment")
                .title(title)
                .excerpt(excerpt)
                .content(upload.description().orElse(""))
                .guid(placed.url());
        upload.status().ifPresent(changes::status);
        upload.author().ifPresent(changes::author);
        upload.parent().ifPresent(changes::parent);
        PostSnapshot post = posts.create(changes.build());

        MediaEntity row = MediaEntity.create(siteId, post.id());
        row.storageKey = placed.key();
        row.mimeType = mimeType;
        row.fileSize = upload.size();
        row.width = header.map(ImageInfo::width).orElse(null);
        row.height = header.map(ImageInfo::height).orElse(null);
        row.imageMeta = imageMeta.orElse(null);
        row.altText = AttachmentFields.alt(upload.altText(), embedded);
        row.processing = MediaEntity.PENDING;
        row.storedBytes = upload.size();
        mediaRows.persist(row);
        mediaRows.flush();

        MediaSnapshot snapshot = JpaMedia.snapshot(post, row, storage, siteInfo, runtimeConfig.mainSiteId());
        transactions.publish(storedEvents, new MediaStored(siteId, now, snapshot));
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

    /** The image's embedded data. */
    private ObjectNode imageMeta(Path file, String imageType) {
        String locale = locale();
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
