package org.quarkpress.media.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.SystemException;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import org.jboss.logging.Logger;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteFeatureDisabledException;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.core.UserId;
import org.quarkpress.lib.wpkses.TextFields;
import org.quarkpress.media.ImageProcessor;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaChanges;
import org.quarkpress.media.MediaException;
import org.quarkpress.media.MediaFeature;
import org.quarkpress.media.MediaPage;
import org.quarkpress.media.MediaQuery;
import org.quarkpress.media.MediaSize;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.MediaStorage;
import org.quarkpress.media.Processing;
import org.quarkpress.media.StoredKey;
import org.quarkpress.media.Upload;
import org.quarkpress.persistence.runtime.QuarkPressRuntimeConfig;
import org.quarkpress.persistence.runtime.TenantIds;
import org.quarkpress.posts.DateCondition;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostOrder;
import org.quarkpress.posts.PostPage;
import org.quarkpress.posts.PostQuery;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.posts.Posts;
import org.quarkpress.posts.SearchColumn;
import org.quarkpress.posts.runtime.Acting;
import org.quarkpress.posts.runtime.PostCapabilityFacts;
import org.quarkpress.posts.runtime.PostEntity;
import org.quarkpress.posts.runtime.PostLocks;
import org.quarkpress.posts.runtime.PostRepository;

/** The media library of the current site, on the posts module's attachments and the media rows. */
@ApplicationScoped
public class JpaMedia implements Media {

    private static final Logger LOG = Logger.getLogger(JpaMedia.class);

    static final String IN_TRANSACTION = "Media.upload and Media.process must be called outside a transaction:"
            + " processing commits each save on its own";

    @Inject
    UploadPipeline pipeline;

    @Inject
    Posts posts;

    @Inject
    MediaRepository mediaRows;

    @Inject
    SiteContext site;

    @Inject
    ActiveStorage storage;

    @Inject
    QuarkPressRuntimeConfig runtimeConfig;

    @Inject
    SizeGenerator generator;

    @Inject
    AsyncProcessing asyncProcessing;

    @Inject
    MediaConfig config;

    @Inject
    TransactionManager transactionManager;

    @Inject
    PostRepository postRows;

    @Inject
    Acting acting;

    @Inject
    PostCapabilityFacts facts;

    @Inject
    PostLocks postLocks;

    @Inject
    PostTypes postTypes;

    @Inject
    AllowedTypesFor allowedTypesFor;

    @Inject
    UploadLimits uploadLimits;

    @Inject
    ImageProcessor processor;

    @Inject
    AsSystem asSystem;

    @Override
    public MediaSnapshot store(Upload upload) {
        return pipeline.store(upload);
    }

    @Override
    public MediaSnapshot upload(Upload upload) {
        return upload(upload, stored -> {});
    }

    @Override
    public MediaSnapshot upload(Upload upload, AfterStore afterStore) {
        Objects.requireNonNull(upload, "upload");
        Objects.requireNonNull(afterStore, "afterStore");
        requireNoTransaction();
        SizeGenerator.Options options = new SizeGenerator.Options(upload.generateSubSizes(), upload.convertFormat());
        if (config.processing() == MediaConfig.ProcessingMode.ASYNC) {
            // the background run waits for the step after storing: queued only once it returned
            MediaSnapshot stored = asyncProcessing.storeDeferred(options, () -> pipeline.store(upload));
            afterStore.stored(stored);
            // the answer shows what the step wrote; it is read before the run can start
            MediaSnapshot answer = reread(stored);
            asyncProcessing.queue(stored.post().site(), stored.post().id());
            return answer;
        }
        // the options are kept with the pending row too, for a later run should this one never end
        MediaSnapshot stored = asyncProcessing.store(options, () -> pipeline.store(upload));
        // a failure here leaves the stored attachment pending, unprocessed
        afterStore.stored(stored);
        try {
            return generator.run(site.siteId(), stored.post().id(), options).orElseGet(() -> reread(stored));
        } catch (RuntimeException | OutOfMemoryError failure) {
            // processing never fails an upload that is stored
            LOG.warnf(
                    failure,
                    "attachment %s could not be processed",
                    stored.post().id().value());
            return reread(stored);
        } catch (Error failure) {
            // not left pending, whatever failed: the run marks it failed, and so does this, should the run not have
            generator.markFailedIfPending(site.siteId(), stored.post().id(), failure);
            throw failure;
        }
    }

    /**
     * The stored attachment as it is now, read in a transaction of its own (the step after storing may have changed
     * it); {@code stored} itself when it cannot be read.
     */
    private MediaSnapshot reread(MediaSnapshot stored) {
        SiteId siteId = stored.post().site();
        PostId id = stored.post().id();
        try {
            return QuarkusTransaction.requiringNew()
                    .call(() -> unchecked(siteId, id))
                    .orElse(stored);
        } catch (RuntimeException failure) {
            LOG.debugf(failure, "attachment %s could not be read again after its upload", id.value());
            return stored;
        }
    }

    /** The attachment {@code id} of {@code siteId} with its media row, as stored; no capability is checked. */
    private Optional<MediaSnapshot> unchecked(SiteId siteId, PostId id) {
        return postRows.findPost(siteId, id)
                .filter(post -> post.type.equals("attachment"))
                .flatMap(post -> mediaRows
                        .findMedia(siteId, id)
                        .map(row -> snapshot(
                                post.toSnapshot(), row, storage.storage(), site.site(), runtimeConfig.mainSiteId())));
    }

    @Override
    public MediaSnapshot process(PostId attachment) {
        Objects.requireNonNull(attachment, "attachment");
        requireNoTransaction();
        requireFeature(site);
        SiteId siteId = site.siteId();
        // the refusal is answered from outside the transaction runner, which may wrap what it rethrows
        RuntimeException refusal = QuarkusTransaction.requiringNew().call(() -> {
            try {
                PostSnapshot post = postRows.findPost(siteId, attachment)
                        .map(PostEntity::toSnapshot)
                        .filter(found -> found.type().equals("attachment"))
                        .orElseThrow(() -> PostException.invalidPost(attachment));
                acting.require(siteId, "edit_post", Optional.of(facts.of(post)));
                return mediaRows.findMedia(siteId, attachment).isEmpty() ? PostException.invalidPost(attachment) : null;
            } catch (RuntimeException refused) {
                return refused;
            }
        });
        if (refusal != null) {
            throw refusal;
        }
        return generator
                .run(siteId, attachment, SizeGenerator.Options.DEFAULT)
                .orElseThrow(() -> PostException.invalidPost(attachment));
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public List<String> missingSizes(PostId attachment) {
        Objects.requireNonNull(attachment, "attachment");
        requireFeature(site);
        // an id that is no attachment of the site is refused as such before the caller's read access is checked
        if (postRows.findPost(site.siteId(), attachment)
                .filter(post -> post.type.equals("attachment"))
                .isEmpty()) {
            throw PostException.invalidPost(attachment);
        }
        MediaSnapshot media = find(attachment).orElseThrow(() -> PostException.invalidPost(attachment));
        return generator.missingSizes(media);
    }

    /** Refuses when a transaction is active: each save of a processing run commits on its own. */
    private void requireNoTransaction() {
        int status;
        try {
            status = transactionManager.getStatus();
        } catch (SystemException failure) {
            throw new IllegalStateException(IN_TRANSACTION, failure);
        }
        if (status != Status.STATUS_NO_TRANSACTION) {
            throw new IllegalStateException(IN_TRANSACTION);
        }
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public Optional<MediaSnapshot> find(PostId attachment) {
        Objects.requireNonNull(attachment, "attachment");
        requireFeature(site);
        return posts.find(attachment)
                .filter(post -> post.type().equals("attachment"))
                .flatMap(post -> mediaRows
                        .findMedia(post.site(), post.id())
                        .map(row -> snapshot(post, row, storage.storage(), site.site(), runtimeConfig.mainSiteId())));
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public Optional<MediaSnapshot> read(PostId attachment) {
        Objects.requireNonNull(attachment, "attachment");
        requireFeature(site);
        return unchecked(site.siteId(), attachment);
    }

    @Override
    public Path spool(InputStream body) throws IOException {
        Objects.requireNonNull(body, "body");
        requireFeature(site);
        Path file = storage.storage().temporaryFile();
        try (OutputStream out = Files.newOutputStream(file)) {
            body.transferTo(out);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException deleteFailure) {
                failure.addSuppressed(deleteFailure);
            }
            throw failure;
        }
        return file;
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public long maxUploadSize() {
        requireFeature(site);
        return uploadLimits.maxUploadSize();
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public List<Map.Entry<String, String>> allowedTypes() {
        requireFeature(site);
        return allowedTypesFor.current().patterns().stream()
                .map(pattern -> Map.entry(pattern.key(), pattern.mimeType()))
                .toList();
    }

    @Override
    public Optional<String> outputType(String mimeType) {
        Objects.requireNonNull(mimeType, "mimeType");
        requireFeature(site);
        if (!processor.supports(mimeType)) {
            return Optional.empty();
        }
        String output = SizePlan.outputType(mimeType, config.effectiveOutputFormats(), processor);
        return output.equals(mimeType) ? Optional.empty() : Optional.of(output);
    }

    @Override
    public boolean refusesImageType(String declaredType, boolean generateSubSizes) {
        Objects.requireNonNull(declaredType, "declaredType");
        requireFeature(site);
        return pipeline.unsupportedImage(declaredType, generateSubSizes);
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public MediaSnapshot update(PostId attachment, MediaChanges changes) {
        Objects.requireNonNull(attachment, "attachment");
        Objects.requireNonNull(changes, "changes");
        requireFeature(site);
        SiteId siteId = site.siteId();
        // every refusal comes before the first write
        PostSnapshot stored = postRows.findPost(siteId, attachment)
                .map(PostEntity::toSnapshot)
                .filter(post -> post.type().equals("attachment"))
                .orElseThrow(() -> PostException.invalidPost(attachment));
        if (mediaRows.findMedia(siteId, attachment).isEmpty()) {
            throw PostException.invalidPost(attachment);
        }
        acting.require(siteId, "edit_post", Optional.of(facts.of(stored)));
        if (changes.parent().isPresent()
                && changes.parent().getAsLong() > 0
                && postRows.findPost(siteId, PostId.of(changes.parent().getAsLong()))
                        .filter(parent -> parent.type.equals("attachment") || parent.type.equals("revision"))
                        .isPresent()) {
            throw new MediaException("rest_invalid_param", UploadPipeline.INVALID_PARENT_TYPE);
        }
        PostChanges.Builder post = PostChanges.builder();
        changes.title().ifPresent(post::title);
        changes.caption().ifPresent(post::excerpt);
        changes.description().ifPresent(post::content);
        changes.parent().ifPresent(post::parent);
        changes.author().ifPresent(post::author);
        changes.status().ifPresent(post::status);
        changes.expectedVersion().ifPresent(post::expectedVersion);
        changes.date().ifPresent(post::date);
        changes.dateGmt().ifPresent(post::dateGmt);
        changes.slug().ifPresent(post::slug);
        changes.commentStatus().ifPresent(post::commentStatus);
        changes.pingStatus().ifPresent(post::pingStatus);
        // the post is saved even for the alternative text alone: WordPress dates and announces that edit too
        PostSnapshot saved = posts.update(attachment, post.build());
        try {
            // the site's post lock comes before the media row in the lock order; posts' save took it already in this
            // transaction, so taking it again is a re-entrant no-op that keeps the order explicit here
            postLocks.lock(siteId);
            MediaEntity row =
                    mediaRows.lockMedia(siteId, attachment).orElseThrow(() -> PostException.invalidPost(attachment));
            changes.altText().ifPresent(alt -> row.altText = TextFields.textField(alt));
            mediaRows.flush();
            return snapshot(saved, row, storage.storage(), site.site(), runtimeConfig.mainSiteId());
        } catch (RuntimeException failure) {
            // the post is written: a failure after it must not leave it for the caller to commit
            markRollbackOnly(failure);
            throw failure;
        }
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public Optional<MediaSnapshot> delete(PostId attachment) {
        Objects.requireNonNull(attachment, "attachment");
        requireFeature(site);
        SiteId siteId = site.siteId();
        if (postRows.findPost(siteId, attachment)
                .filter(post -> post.type.equals("attachment"))
                .isEmpty()) {
            return Optional.empty();
        }
        Optional<MediaEntity> row = mediaRows.findMedia(siteId, attachment);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        // the deletion reads the row again under its lock, so the snapshot is the attachment as it was deleted
        return posts.delete(attachment, true)
                .map(before -> snapshot(before, row.get(), storage.storage(), site.site(), runtimeConfig.mainSiteId()));
    }

    /** The most ids one query of media rows binds, so that an unpaged query stays within the databases' limits. */
    static final int ROW_CHUNK = 1000;

    /** {@code values} in consecutive pieces of at most {@code size}, in order. */
    static <T> List<List<T>> chunks(List<T> values, int size) {
        List<List<T>> chunks = new ArrayList<>();
        for (int start = 0; start < values.size(); start += size) {
            chunks.add(values.subList(start, Math.min(values.size(), start + size)));
        }
        return chunks;
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public MediaPage query(MediaQuery query) {
        Objects.requireNonNull(query, "query");
        requireFeature(site);
        SiteId siteId = site.siteId();
        PostQuery postQuery = postQuery(siteId, query);
        // no capability of the caller is asked: an unfiltered query is for a caller that decides per row
        PostPage page = query.unfiltered() ? asSystem.call(() -> posts.query(postQuery)) : posts.query(postQuery);
        if (query.countOnly()) {
            return new MediaPage(List.of(), page.found(), page.maxPages());
        }
        List<Long> ids = page.posts().stream().map(post -> post.id().value()).toList();
        Map<Long, MediaEntity> rows = new LinkedHashMap<>();
        for (List<Long> chunk : chunks(ids, ROW_CHUNK)) {
            mediaRows
                    .find("siteId = ?1 and postId in ?2", TenantIds.param(siteId), chunk)
                    .list()
                    .forEach(row -> rows.put(row.postId, row));
        }
        MediaStorage files = storage.storage();
        long mainSiteId = runtimeConfig.mainSiteId();
        List<MediaSnapshot> items = new ArrayList<>(ids.size());
        for (PostSnapshot post : page.posts()) {
            MediaEntity row = rows.get(post.id().value());
            if (row != null) {
                items.add(snapshot(post, row, files, site.site(), mainSiteId));
            }
        }
        return new MediaPage(items, page.found(), page.maxPages());
    }

    /**
     * The post query of type {@code attachment} for {@code query}: no statuses means {@code inherit}, plus {@code
     * private} for a caller who may read others' private attachments; the media condition of {@link
     * MimeConditionProvider} is always added, with the MIME types as an array of texts (or, for exact types, as an
     * object holding them), and so is the search of the file path ({@link FilePathSearch}).
     */
    private PostQuery postQuery(SiteId siteId, MediaQuery query) {
        PostQuery.Builder builder = PostQuery.builder().types("attachment");
        if (!query.statuses().isEmpty()) {
            builder.statuses(query.statuses().toArray(String[]::new));
        } else if (acting.can(
                siteId, postTypes.capabilitiesOrPost("attachment").readPrivatePosts(), Optional.empty())) {
            builder.statuses("inherit", "private");
        } else {
            builder.statuses("inherit");
        }
        // always: an attachment without a media row is no media, neither listed nor counted
        ArrayNode types = JsonNodeFactory.instance.arrayNode();
        if (query.exactMimeTypes().isEmpty()) {
            query.mimeTypes().forEach(types::add);
            builder.condition(MimeConditionProvider.KEY, types);
        } else {
            query.exactMimeTypes().forEach(types::add);
            builder.condition(
                    MimeConditionProvider.KEY,
                    JsonNodeFactory.instance.objectNode().set(MimeConditionProvider.EXACT, types));
        }
        query.parent().ifPresent(builder::parent);
        // 0 stands for the unattached attachments in both lists
        builder.parentIn(query.parentIn().stream().mapToLong(Long::longValue).toArray());
        builder.parentNotIn(
                query.parentNotIn().stream().mapToLong(Long::longValue).toArray());
        query.author().ifPresent(builder::author);
        builder.authorIn(query.authorIn().toArray(UserId[]::new));
        builder.authorNotIn(query.authorNotIn().toArray(UserId[]::new));
        builder.postIn(query.include().toArray(PostId[]::new));
        builder.postNotIn(query.exclude().toArray(PostId[]::new));
        builder.nameIn(query.slugs().toArray(String[]::new));
        query.search().ifPresent(builder::search);
        builder.exactSearch(query.exactSearch());
        builder.searchColumns(query.searchColumns().toArray(SearchColumn[]::new));
        builder.searchAlso(FilePathSearch.KEY);
        for (DateCondition condition : query.dates()) {
            builder.date(condition);
        }
        for (PostOrder order : query.orderBy()) {
            builder.orderBy(order.key(), order.ascending());
        }
        query.perPage().ifPresent(builder::perPage);
        builder.page(query.page());
        query.offset().ifPresent(builder::offset);
        return builder.build();
    }

    private void markRollbackOnly(RuntimeException failure) {
        try {
            transactionManager.setRollbackOnly();
        } catch (SystemException | IllegalStateException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    /** Refuses when the current site's media feature is off. */
    static void requireFeature(SiteContext site) {
        if (!site.features().isEnabled(MediaFeature.KEY)) {
            throw new SiteFeatureDisabledException(MediaFeature.KEY);
        }
    }

    /** The attachment {@code post} with its media row {@code row}, on {@code site}. */
    static MediaSnapshot snapshot(
            PostSnapshot post, MediaEntity row, MediaStorage storage, SiteInfo site, long mainSiteId) {
        String base =
                site.id().value() == mainSiteId ? "" : "sites/" + site.id().value() + "/";
        String attachedFile =
                row.storageKey.startsWith(base) ? row.storageKey.substring(base.length()) : row.storageKey;
        Map<String, MediaSize> sizes = sizes(row.sizes);
        String url = storage.publicUrl(site, row.storageKey);
        return new MediaSnapshot(
                post,
                row.mimeType,
                row.storageKey,
                attachedFile,
                Optional.ofNullable(row.originalKey).map(key -> key.substring(key.lastIndexOf('/') + 1)),
                row.fileSize,
                row.width == null ? OptionalInt.empty() : OptionalInt.of(row.width),
                row.height == null ? OptionalInt.empty() : OptionalInt.of(row.height),
                sizes,
                row.imageMeta instanceof ObjectNode meta ? Optional.of(meta) : Optional.empty(),
                // the upload's options kept for its processing are no metadata of the attachment
                SizeGenerator.withoutOptions(
                        row.extra instanceof ObjectNode extra ? extra : JsonNodeFactory.instance.objectNode()),
                row.altText,
                Processing.valueOf(row.processing.toUpperCase(Locale.ROOT)),
                url,
                sizeUrls(sizes, row.storageKey, url, storage, site));
    }

    /**
     * The public URL of each size, the storage's URL of its file in the main file's folder. A size whose file name
     * makes no valid storage key (an imported entry, never stored by a storage) gets the main URL's folder and its
     * name, as WordPress builds every size URL.
     */
    private static Map<String, String> sizeUrls(
            Map<String, MediaSize> sizes, String storageKey, String url, MediaStorage storage, SiteInfo site) {
        String folder = storageKey.substring(0, storageKey.lastIndexOf('/') + 1);
        Map<String, String> urls = new LinkedHashMap<>();
        sizes.forEach((name, size) -> {
            String sizeUrl;
            try {
                sizeUrl = storage.publicUrl(site, StoredKey.require(folder + size.file()));
            } catch (IllegalArgumentException notAKey) {
                sizeUrl = url.substring(0, url.lastIndexOf('/') + 1) + size.file();
            }
            urls.put(name, sizeUrl);
        });
        return urls;
    }

    /** The sizes of a media row's {@code sizes} column, in order; entries without a file name are skipped. */
    private static Map<String, MediaSize> sizes(JsonNode column) {
        Map<String, MediaSize> sizes = new LinkedHashMap<>();
        if (column instanceof ObjectNode entries) {
            entries.properties().forEach(entry -> {
                JsonNode size = entry.getValue();
                if (size.path("file").isTextual()) {
                    sizes.put(
                            entry.getKey(),
                            new MediaSize(
                                    size.path("file").textValue(),
                                    size.path("width").asInt(),
                                    size.path("height").asInt(),
                                    size.path("mime-type").asText(""),
                                    size.path("filesize").asLong()));
                }
            });
        }
        return sizes;
    }
}
