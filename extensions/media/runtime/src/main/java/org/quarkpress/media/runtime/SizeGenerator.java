package org.quarkpress.media.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import org.jboss.logging.Logger;
import org.quarkpress.core.PostId;
import org.quarkpress.core.QuarkPressClock;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.media.DecodedImage;
import org.quarkpress.media.Encoded;
import org.quarkpress.media.ImageInfo;
import org.quarkpress.media.ImageProcessingException;
import org.quarkpress.media.ImageProcessor;
import org.quarkpress.media.ImageSizeDefinition;
import org.quarkpress.media.ImageSizes;
import org.quarkpress.media.KeyTakenException;
import org.quarkpress.media.MediaProcessed;
import org.quarkpress.media.MediaSize;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.MediaStorage;
import org.quarkpress.media.Processing;
import org.quarkpress.media.WriteMode;
import org.quarkpress.persistence.runtime.OptionTransactions;
import org.quarkpress.persistence.runtime.QuarkPressRuntimeConfig;
import org.quarkpress.posts.runtime.PostEntity;
import org.quarkpress.posts.runtime.PostRepository;

/**
 * Makes the generated files of an attachment as WordPress's server path does, and saves its metadata as it goes.
 *
 * <p>A new upload is processed from scratch: an image whose type and dimensions are read from its header gets, when
 * the image processor handles its type and it is within the pixel limit, one main-file copy when one applies (scaled
 * down above the big-image threshold and turned upright from its JPEG orientation, else converted to the output type,
 * else turned upright), then its registered sizes, cut from the upright original decoded once. Audio and video get
 * the metadata their container states; anything else just its file size. A post-process of an attachment that was
 * processed before makes only its missing sizes, from the original.
 *
 * <p>Every save is a transaction of its own that locks the media row, reads it again and writes it whole: the first
 * one stores the base metadata (and the main-file copy), then one per size, then a last one that sets the final
 * processing state and announces the run. A file is stored inside the transaction of the save that names it, under
 * the row lock, create-exclusive (a taken name gets a number), and removed again when that transaction does not
 * commit, so a stored entry always names an existing file and a file is never stored for a row that is gone (the run
 * ends there). Two runs of one attachment at once therefore agree: a size the other run saved first is kept, never
 * made twice. Decodes wait for a {@link DecodeLimiter} permit, held until the run's last size. A failing decode, cut
 * or encode never fails the run: the size is left out and the attachment ends {@link Processing#PARTIAL}; only a
 * failing save marks it {@link Processing#FAILED}.
 */
@ApplicationScoped
public class SizeGenerator {

    private static final Logger LOG = Logger.getLogger(SizeGenerator.class);

    /** The encode quality of every lossy copy. */
    static final int QUALITY = 82;

    /** How many numbered names are tried when a copy's name is taken by a file of something else. */
    static final int MAX_NAME_ATTEMPTS = 10;

    /**
     * The key of the stored metadata that keeps an upload's options (no sizes, no format conversion) while it is
     * pending, so that a later run honours them; every save of a run from scratch removes it.
     */
    public static final String UPLOAD_OPTIONS = "_quarkpress_upload";

    /** The real image types WordPress processes as images (its displayable types). */
    static final Set<String> DISPLAYABLE =
            Set.of("image/gif", "image/jpeg", "image/png", "image/bmp", ImageHeaders.ICON, "image/webp", "image/avif");

    private static final String JPEG = "image/jpeg";

    @Inject
    MediaRepository mediaRows;

    @Inject
    PostRepository postRows;

    @Inject
    ActiveStorage activeStorage;

    @Inject
    ImageProcessor processor;

    @Inject
    MediaConfig config;

    @Inject
    ImageSizes imageSizes;

    @Inject
    DecodeLimiter limiter;

    @Inject
    StoredFiles storedFiles;

    @Inject
    OptionTransactions transactions;

    @Inject
    Event<MediaProcessed> processedEvents;

    @Inject
    QuarkPressClock clock;

    @Inject
    SiteContext site;

    @Inject
    QuarkPressRuntimeConfig runtimeConfig;

    /**
     * What an upload asked for: whether sizes (and any main-file copy) are made at all, and whether the output type
     * mapping applies.
     */
    public record Options(boolean generateSubSizes, boolean convertFormat) {

        /** Sizes made, output types mapped. */
        public static final Options DEFAULT = new Options(true, true);

        /** These options as the stored metadata keeps them. */
        public ObjectNode toJson() {
            return JsonNodeFactory.instance
                    .objectNode()
                    .put("generate_sub_sizes", generateSubSizes)
                    .put("convert_format", convertFormat);
        }

        /** The options a pending attachment's stored metadata keeps, when it keeps any. */
        static Optional<Options> of(ObjectNode extra) {
            JsonNode stored = extra.get(UPLOAD_OPTIONS);
            if (stored == null || !stored.isObject()) {
                return Optional.empty();
            }
            return Optional.of(new Options(
                    !stored.path("generate_sub_sizes").isBoolean()
                            || stored.get("generate_sub_sizes").booleanValue(),
                    !stored.path("convert_format").isBoolean()
                            || stored.get("convert_format").booleanValue()));
        }
    }

    /**
     * Processes the attachment {@code post} of the current site {@code siteId}: from scratch while it is pending,
     * otherwise its missing sizes. Call it outside any transaction. Returns the attachment as the run left it, or
     * empty when it is gone.
     */
    public Optional<MediaSnapshot> run(SiteId siteId, PostId post, Options options) {
        Optional<MediaSnapshot> start = read(siteId, post);
        if (start.isEmpty()) {
            return Optional.empty();
        }
        Map<String, ImageSizeDefinition> registered = inTransaction(imageSizes::registered);
        // the options the upload kept with its row win over the defaults of a later run
        Options effective = storedOptions(siteId, post).orElse(options);
        Run run = new Run(siteId, post, start.get());
        try {
            if (start.get().processing() == Processing.PENDING) {
                return run.fromScratch(effective, registered);
            }
            return Optional.of(run.missing(registered, effective));
        } catch (SaveFailed failure) {
            LOG.warnf(failure.getCause(), "the metadata of attachment %s could not be saved", post.value());
            markFailed(siteId, post);
            return read(siteId, post);
        } catch (RuntimeException | OutOfMemoryError failure) {
            // whatever went wrong, the attachment is never left pending: a later post-process makes what is missing
            LOG.warnf(failure, "attachment %s could not be processed; it is left partial", post.value());
            markPartialIfPending(siteId, post);
            return read(siteId, post);
        }
    }

    /** The options the attachment's row keeps from its upload, when it keeps any. */
    private Optional<Options> storedOptions(SiteId siteId, PostId post) {
        return inTransaction(() -> mediaRows
                .findMedia(siteId, post)
                .filter(row -> row.extra instanceof ObjectNode)
                .flatMap(row -> Options.of((ObjectNode) row.extra)));
    }

    private void markPartialIfPending(SiteId siteId, PostId post) {
        try {
            QuarkusTransaction.requiringNew()
                    .run(() -> mediaRows
                            .lockMedia(siteId, post)
                            .filter(row -> MediaEntity.PENDING.equals(row.processing))
                            .ifPresent(row -> row.processing = MediaEntity.PARTIAL));
        } catch (RuntimeException failure) {
            LOG.warnf(failure, "attachment %s could not be marked as partial", post.value());
        }
    }

    /** The missing sizes of {@code media}, reading the original's dimensions from its file when there is one. */
    public List<String> missingSizes(MediaSnapshot media) {
        Map<String, ImageSizeDefinition> registered = imageSizes.registered();
        Optional<ImageInfo> original = Optional.empty();
        if (media.originalImage().isPresent() && media.mimeType().startsWith("image/")) {
            original =
                    headerOf(folder(media.storageKey()) + media.originalImage().get());
        }
        return MissingSizes.of(media, registered, original);
    }

    // ---- reading -------------------------------------------------------------------------------------------------

    private Optional<MediaSnapshot> read(SiteId siteId, PostId post) {
        return inTransaction(() -> mediaRows.findMedia(siteId, post).flatMap(row -> snapshot(siteId, row)));
    }

    private Optional<MediaSnapshot> snapshot(SiteId siteId, MediaEntity row) {
        return postRows.findPost(siteId, PostId.of(row.postId))
                .map(PostEntity::toSnapshot)
                .map(post ->
                        JpaMedia.snapshot(post, row, activeStorage.storage(), site.site(), runtimeConfig.mainSiteId()));
    }

    /** The header of the stored file {@code key}; empty when it cannot be read. */
    private Optional<ImageInfo> headerOf(String key) {
        Path copy = null;
        try {
            copy = copy(key, ImageHeaders.HEADER_LIMIT + ImageHeaders.PREFIX);
            return header(copy);
        } catch (IOException | UncheckedIOException failure) {
            LOG.debugf(failure, "the stored file %s could not be read", key);
            return Optional.empty();
        } finally {
            deleteQuietly(copy);
        }
    }

    private static Optional<ImageInfo> header(Path file) {
        try {
            return ImageHeaders.read(file);
        } catch (IOException | RuntimeException | StackOverflowError | OutOfMemoryError failure) {
            LOG.debugf("an image header could not be read: %s", failure);
            return Optional.empty();
        }
    }

    /** A temporary copy of the stored file {@code key}, of at most {@code limit} bytes. */
    private Path copy(String key, long limit) throws IOException {
        Path copy = Files.createTempFile("quarkpress-media-", ".tmp");
        try (InputStream in = activeStorage.storage().open(key)) {
            if (limit == Long.MAX_VALUE) {
                Files.copy(in, copy, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.write(copy, in.readNBytes((int) Math.min(limit, Integer.MAX_VALUE - 8)));
            }
            return copy;
        } catch (IOException | RuntimeException failure) {
            deleteQuietly(copy);
            throw failure;
        }
    }

    private static void deleteQuietly(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException failure) {
                LOG.debugf(failure, "the temporary file %s could not be removed", file);
            }
        }
    }

    // ---- saving --------------------------------------------------------------------------------------------------

    /** Turning an image upright failed. */
    private static final class TurnFailed extends RuntimeException {
        TurnFailed(Throwable cause) {
            super("the image could not be turned upright: " + cause.getMessage(), cause);
        }
    }

    /** A save that failed in the database; the run marks the attachment failed. */
    private static final class SaveFailed extends RuntimeException {
        SaveFailed(RuntimeException cause) {
            super(cause);
        }
    }

    /** What a save's work answers: a value, or that the row is gone. */
    private record Saved<T>(boolean gone, T value) {}

    /** Runs {@code work} on the row, locked and read again, in a transaction of its own. */
    private <T> Saved<T> save(SiteId siteId, PostId post, RowWork<T> work) {
        try {
            return QuarkusTransaction.requiringNew().call(() -> {
                Optional<MediaEntity> row = mediaRows.lockMedia(siteId, post);
                if (row.isEmpty()) {
                    return new Saved<T>(true, null);
                }
                T value = work.apply(row.get());
                mediaRows.flush();
                return new Saved<>(false, value);
            });
        } catch (RuntimeException failure) {
            throw new SaveFailed(failure);
        }
    }

    @FunctionalInterface
    private interface RowWork<T> {
        T apply(MediaEntity row) throws Exception;
    }

    private <T> T inTransaction(Callable<T> work) {
        return QuarkusTransaction.requiringNew().call(work);
    }

    private void markFailed(SiteId siteId, PostId post) {
        try {
            QuarkusTransaction.requiringNew()
                    .run(() -> mediaRows.lockMedia(siteId, post).ifPresent(row -> row.processing = MediaEntity.FAILED));
        } catch (RuntimeException failure) {
            LOG.warnf(failure, "attachment %s could not be marked as failed", post.value());
        }
    }

    /** Sets the final state, announces the run and answers the attachment as it now is. */
    private MediaSnapshot finish(SiteId siteId, MediaEntity row, String processing) {
        row.processing = processing;
        if (MediaEntity.DONE.equals(processing) && row.extra instanceof ObjectNode extra) {
            // kept while anything is left to make, so a post-process still honours them
            row.extra = withoutOptions(extra);
        }
        mediaRows.flush();
        MediaSnapshot snapshot = snapshot(siteId, row)
                .orElseThrow(() -> new IllegalStateException("the attachment post of a media row is gone"));
        transactions.publish(processedEvents, new MediaProcessed(siteId, clock.now(), snapshot));
        return snapshot;
    }

    /**
     * Stores {@code file} as {@code name} in {@code folder}, create-exclusive: a name another file holds is numbered
     * ({@code name-1.jpg}, ...). Inside the save's transaction: the file goes again when it does not commit. Answers
     * the name it was stored under and the size the storage holds (the written size when it tells none), or empty
     * when it could not be stored.
     */
    private Optional<Placed> place(String folder, String name, Path file, String mimeType, long written) {
        MediaStorage storage = activeStorage.storage();
        for (int attempt = 0; attempt <= MAX_NAME_ATTEMPTS; attempt++) {
            String candidate = attempt == 0 ? name : numbered(name, attempt);
            String key = folder + candidate;
            try {
                storage.put(key, file, mimeType, WriteMode.CREATE_NEW);
            } catch (KeyTakenException taken) {
                LOG.debugf("the generated file name %s is taken; numbering it", key);
                continue;
            } catch (IOException | UncheckedIOException | IllegalArgumentException failure) {
                LOG.warnf(failure, "the generated file %s could not be stored", key);
                return Optional.empty();
            }
            try {
                storedFiles.removeOnRollback(key);
            } catch (RuntimeException failure) {
                storage.delete(key);
                throw failure;
            }
            return Optional.of(new Placed(candidate, storage.size(key).orElse(written)));
        }
        LOG.warnf("no free name was found for the generated file %s%s", folder, name);
        return Optional.empty();
    }

    /** A file stored for a run: its name in the folder and its size in the storage. */
    private record Placed(String name, long bytes) {}

    /** {@code name} with {@code -n} before its last extension. */
    static String numbered(String name, int n) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name + "-" + n : name.substring(0, dot) + "-" + n + name.substring(dot);
    }

    private static String folder(String key) {
        return key.substring(0, key.lastIndexOf('/') + 1);
    }

    private static String fileName(String key) {
        return key.substring(key.lastIndexOf('/') + 1);
    }

    private static ObjectNode entry(MediaSize size) {
        return JsonNodeFactory.instance
                .objectNode()
                .put("file", size.file())
                .put("width", size.width())
                .put("height", size.height())
                .put("mime-type", size.mimeType())
                .put("filesize", size.fileSize());
    }

    /** {@code extra} without the upload's options. */
    static ObjectNode withoutOptions(ObjectNode extra) {
        if (extra.has(UPLOAD_OPTIONS)) {
            ObjectNode copy = extra.deepCopy();
            copy.remove(UPLOAD_OPTIONS);
            return copy;
        }
        return extra;
    }

    private static ObjectNode sizesOf(MediaEntity row) {
        return row.sizes instanceof ObjectNode sizes ? sizes.deepCopy() : JsonNodeFactory.instance.objectNode();
    }

    /**
     * The entry of another size than {@code name} whose file is {@code file}, when there is one: a file two sizes
     * share.
     */
    private static Optional<MediaSize> withFile(ObjectNode sizes, String file, String name) {
        for (Map.Entry<String, JsonNode> size : sizes.properties()) {
            JsonNode value = size.getValue();
            if (!size.getKey().equals(name) && file.equals(value.path("file").textValue())) {
                return Optional.of(new MediaSize(
                        file,
                        value.path("width").asInt(),
                        value.path("height").asInt(),
                        value.path("mime-type").asText(""),
                        value.path("filesize").asLong()));
            }
        }
        return Optional.empty();
    }

    /** The EXIF orientation an image's metadata holds: its number, or 0 when absent or not a number. */
    static int orientation(Optional<ObjectNode> imageMeta) {
        JsonNode value = imageMeta.map(meta -> meta.get("orientation")).orElse(null);
        if (value == null) {
            return 0;
        }
        if (value.isIntegralNumber()) {
            return value.canConvertToInt() ? value.intValue() : 0;
        }
        String text = value.isTextual() ? value.textValue() : "";
        if (text.isEmpty() || text.length() > 9) {
            return 0;
        }
        int number = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return 0;
            }
            number = number * 10 + (c - '0');
        }
        return number;
    }

    // ---- one run -------------------------------------------------------------------------------------------------

    /** A size to make: its name, its cut and its file name. */
    private record Planned(String name, Dimensions.Region region, String file) {}

    /** A main-file copy written to a temporary file. */
    private record MainCopy(String name, Path file, Encoded encoded, boolean rotated) {}

    /** The images of a run, closed together. */
    private static final class Images implements AutoCloseable {
        private final Map<DecodedImage, Boolean> open = new IdentityHashMap<>();
        private final List<DecodedImage> order = new ArrayList<>();

        DecodedImage add(DecodedImage image) {
            if (open.put(image, Boolean.TRUE) == null) {
                order.add(image);
            }
            return image;
        }

        @Override
        public void close() {
            for (int i = order.size() - 1; i >= 0; i--) {
                try {
                    order.get(i).close();
                } catch (RuntimeException failure) {
                    LOG.debugf(failure, "a decoded image could not be released");
                }
            }
            order.clear();
            open.clear();
        }
    }

    private final class Run {

        private final SiteId siteId;
        private final PostId post;
        private final MediaSnapshot media;
        private final List<Path> temporary = new ArrayList<>();

        /** The finished attachment, when the first save already ended the run. */
        private MediaSnapshot finished;

        Run(SiteId siteId, PostId post, MediaSnapshot media) {
            this.siteId = siteId;
            this.post = post;
            this.media = media;
        }

        private String sourceKey() {
            return media.originalImage()
                    .map(name -> folder(media.storageKey()) + name)
                    .orElse(media.storageKey());
        }

        /** A new upload: the dispatch by type, the main-file step and every size. */
        Optional<MediaSnapshot> fromScratch(Options options, Map<String, ImageSizeDefinition> registered) {
            Path source;
            try {
                source = copy(sourceKey(), Long.MAX_VALUE);
            } catch (IOException | RuntimeException failure) {
                LOG.warnf(failure, "the file of attachment %s could not be read; it is marked failed", post.value());
                return last(row -> MediaEntity.FAILED);
            }
            temporary.add(source);
            try {
                return process(source, options, registered);
            } finally {
                temporary.forEach(SizeGenerator::deleteQuietly);
            }
        }

        private Optional<MediaSnapshot> process(
                Path source, Options options, Map<String, ImageSizeDefinition> registered) {
            String mimeType = media.mimeType();
            if (mimeType.startsWith("video/") || mimeType.startsWith("audio/")) {
                ObjectNode av;
                try {
                    av = AvMetadata.read(source, mimeType);
                } catch (IOException failure) {
                    av = JsonNodeFactory.instance.objectNode().put("filesize", media.fileSize());
                }
                ObjectNode metadata = av;
                return last(row -> {
                    row.extra = metadata.deepCopy();
                    row.width = metadata.has("width") ? metadata.get("width").intValue() : null;
                    row.height = metadata.has("height") ? metadata.get("height").intValue() : null;
                    return MediaEntity.DONE;
                });
            }
            Optional<ImageInfo> header = header(source);
            boolean image = mimeType.equals("image/heic")
                    || (mimeType.startsWith("image/")
                            && header.map(info -> DISPLAYABLE.contains(info.mimeType()))
                                    .orElse(false));
            if (!image || header.isEmpty()) {
                // without readable dimensions WordPress keeps the file size only
                return last(row -> MediaEntity.DONE);
            }
            ImageInfo info = header.get();
            int w0 = info.width();
            int h0 = info.height();
            String realType = info.mimeType();
            if (!options.generateSubSizes() || !decodable(info)) {
                return last(row -> {
                    row.width = w0;
                    row.height = h0;
                    return MediaEntity.DONE;
                });
            }
            Map<String, String> formats = options.convertFormat() ? config.effectiveOutputFormats() : Map.of();
            String outputType = SizePlan.outputType(realType, formats, processor);
            String sourceName = fileName(sourceKey());
            int orientation = realType.equals(JPEG) ? orientation(media.imageMeta()) : 0;
            try (DecodeLimiter.Permit permit = limiter.acquire();
                    Images images = new Images()) {
                DecodedImage original;
                try {
                    original = images.add(processor.decode(source, realType));
                } catch (ImageProcessingException | RuntimeException | OutOfMemoryError failure) {
                    decodeFailed(failure);
                    return last(row -> {
                        row.width = w0;
                        row.height = h0;
                        return MediaEntity.PARTIAL;
                    });
                }
                DecodedImage upright;
                try {
                    upright = upright(original, orientation, images);
                } catch (TurnFailed failure) {
                    // sizes cut sideways would never be made again: as if the image could not be decoded
                    decodeFailed(failure);
                    return last(row -> {
                        row.width = w0;
                        row.height = h0;
                        return MediaEntity.PARTIAL;
                    });
                }
                boolean[] mainFailed = {false};
                MainCopy main = null;
                try {
                    main = mainCopy(original, upright, orientation, w0, h0, realType, outputType, sourceName, images);
                } catch (ImageProcessingException | RuntimeException | OutOfMemoryError failure) {
                    mainFailed[0] = true;
                    LOG.warnf(
                            "the main-file copy of attachment %s could not be made: %s",
                            post.value(), failure.getMessage());
                }
                List<Planned> planned = plan(registered, upright, sourceName, realType, outputType);
                MainCopy copy = main;
                Saved<String> first = save(siteId, post, row -> {
                    if (!MediaEntity.PENDING.equals(row.processing)) {
                        // another run saved the base metadata first; its main file stands
                        mainFailed[0] = false;
                        return row.processing;
                    }
                    row.width = w0;
                    row.height = h0;
                    if (copy != null && !useMainCopy(row, copy)) {
                        mainFailed[0] = true;
                    }
                    if (planned.isEmpty()) {
                        String state = mainFailed[0] ? MediaEntity.PARTIAL : MediaEntity.DONE;
                        finished = finish(siteId, row, state);
                        return state;
                    }
                    row.processing = MediaEntity.PARTIAL;
                    return MediaEntity.PARTIAL;
                });
                if (first.gone()) {
                    return Optional.empty();
                }
                if (finished != null) {
                    return Optional.of(finished);
                }
                return sizes(planned, upright, outputType, mainFailed[0]);
            }
        }

        /**
         * Stores {@code copy} as the row's main file, the untouched upload kept as its original: its dimensions and
         * size become the row's, and the orientation of a turned image 1. False when it could not be stored.
         */
        private boolean useMainCopy(MediaEntity row, MainCopy copy) {
            Optional<Placed> stored = place(
                    folder(row.storageKey),
                    copy.name(),
                    copy.file(),
                    copy.encoded().mimeType(),
                    copy.encoded().bytes());
            if (stored.isEmpty()) {
                return false;
            }
            row.originalKey = row.storageKey;
            row.storageKey = folder(row.storageKey) + stored.get().name();
            row.width = copy.encoded().width();
            row.height = copy.encoded().height();
            row.fileSize = stored.get().bytes();
            row.storedBytes += stored.get().bytes();
            if (copy.rotated() && row.imageMeta instanceof ObjectNode meta && meta.has("orientation")) {
                ObjectNode upright = meta.deepCopy();
                upright.put("orientation", 1);
                row.imageMeta = upright;
            }
            return true;
        }

        /**
         * A post-process: the missing sizes, from the original, and the main-file copy again when an earlier run could
         * not make it (the attachment is partial and has no original image while a copy applies). Unchanged, and
         * nothing written, when nothing can be made.
         */
        MediaSnapshot missing(Map<String, ImageSizeDefinition> registered, Options options) {
            boolean mayLackCopy = media.processing() == Processing.PARTIAL
                    && media.originalImage().isEmpty()
                    && media.mimeType().startsWith("image/");
            if (!mayLackCopy
                    && media.originalImage().isEmpty()
                    && MissingSizes.of(media, registered, Optional.empty()).isEmpty()) {
                // nothing missing: not even the file is read
                return media;
            }
            Path source;
            try {
                source = copy(sourceKey(), Long.MAX_VALUE);
            } catch (IOException | RuntimeException failure) {
                LOG.warnf(failure, "the original file of attachment %s could not be read", post.value());
                return media;
            }
            temporary.add(source);
            try {
                Optional<ImageInfo> header = header(source);
                if (header.isEmpty() || !decodable(header.get())) {
                    return media;
                }
                Optional<ImageInfo> original = media.originalImage().isPresent() ? header : Optional.empty();
                List<String> names = MissingSizes.of(media, registered, original);
                ImageInfo info = header.get();
                String realType = info.mimeType();
                Map<String, String> formats = options.convertFormat() ? config.effectiveOutputFormats() : Map.of();
                String outputType = SizePlan.outputType(realType, formats, processor);
                int orientation = realType.equals(JPEG) ? fileOrientation(source, realType) : 0;
                boolean retryCopy = mayLackCopy && copyApplies(info, orientation, outputType);
                if (names.isEmpty() && !retryCopy) {
                    return media;
                }
                Map<String, ImageSizeDefinition> ordered = new LinkedHashMap<>();
                for (Map.Entry<String, ImageSizeDefinition> size : registered.entrySet()) {
                    if (names.contains(size.getKey())) {
                        ordered.put(size.getKey(), size.getValue());
                    }
                }
                try (DecodeLimiter.Permit permit = limiter.acquire();
                        Images images = new Images()) {
                    DecodedImage decoded;
                    try {
                        decoded = images.add(processor.decode(source, realType));
                    } catch (ImageProcessingException | RuntimeException | OutOfMemoryError failure) {
                        decodeFailed(failure);
                        return media;
                    }
                    DecodedImage upright;
                    try {
                        upright = upright(decoded, orientation, images);
                    } catch (TurnFailed failure) {
                        decodeFailed(failure);
                        return media;
                    }
                    boolean copyFailed = false;
                    if (retryCopy) {
                        copyFailed = !retryMainCopy(decoded, upright, orientation, info, outputType, images);
                    }
                    List<Planned> planned = plan(ordered, upright, fileName(sourceKey()), realType, outputType);
                    if (planned.isEmpty() && !retryCopy) {
                        return media;
                    }
                    return sizes(planned, upright, outputType, copyFailed).orElse(media);
                }
            } finally {
                temporary.forEach(SizeGenerator::deleteQuietly);
            }
        }

        /** Whether the main-file step would make a copy of this image: scaled, converted or turned upright. */
        private boolean copyApplies(ImageInfo info, int orientation, String outputType) {
            int threshold = config.bigImageThreshold();
            if (threshold > 0 && (info.width() > threshold || info.height() > threshold)) {
                return SizePlan.scaled(info.width(), info.height(), threshold).isPresent();
            }
            return !outputType.equals(info.mimeType()) || (orientation >= 2 && orientation <= 8);
        }

        /** Makes the main-file copy again and saves it, unless another run did; false when it failed again. */
        private boolean retryMainCopy(
                DecodedImage decoded,
                DecodedImage upright,
                int orientation,
                ImageInfo info,
                String outputType,
                Images images) {
            MainCopy copy;
            try {
                copy = mainCopy(
                        decoded,
                        upright,
                        orientation,
                        info.width(),
                        info.height(),
                        info.mimeType(),
                        outputType,
                        fileName(sourceKey()),
                        images);
            } catch (ImageProcessingException | RuntimeException | OutOfMemoryError failure) {
                LOG.warnf(
                        "the main-file copy of attachment %s could not be made: %s",
                        post.value(), failure.getMessage());
                return false;
            }
            if (copy == null) {
                return true;
            }
            Saved<Boolean> saved = save(siteId, post, row -> row.originalKey != null || useMainCopy(row, copy));
            return !saved.gone() && Boolean.TRUE.equals(saved.value());
        }

        /** The JPEG orientation of the original file itself (the stored metadata may say 1 after a rotated copy). */
        private int fileOrientation(Path source, String realType) {
            try {
                return orientation(Optional.of(ImageMetaReader.read(source, realType, "en_US")));
            } catch (IOException | RuntimeException | StackOverflowError | OutOfMemoryError failure) {
                LOG.debugf("the orientation of attachment %s could not be read: %s", post.value(), failure);
                return 0;
            }
        }

        private boolean decodable(ImageInfo info) {
            return processor.supports(info.mimeType())
                    && (long) info.width() * info.height() <= config.maxImagePixels();
        }

        private void decodeFailed(Throwable failure) {
            LOG.warnf(
                    "the image of attachment %s could not be decoded; it is kept without generated sizes: %s",
                    post.value(), failure.getMessage());
        }

        /**
         * {@code image} turned upright; {@code image} itself when its orientation turns nothing.
         *
         * @throws TurnFailed when turning it fails, whatever the processor threw
         */
        private DecodedImage upright(DecodedImage image, int orientation, Images images) {
            if (orientation < 2 || orientation > 8) {
                return image;
            }
            try {
                return images.add(image.oriented(orientation));
            } catch (RuntimeException | OutOfMemoryError failure) {
                throw new TurnFailed(failure);
            }
        }

        /** The main-file copy, when one applies: scaled, else converted, else turned upright. */
        private MainCopy mainCopy(
                DecodedImage original,
                DecodedImage upright,
                int orientation,
                int w0,
                int h0,
                String realType,
                String outputType,
                String sourceName,
                Images images)
                throws ImageProcessingException {
            int threshold = config.bigImageThreshold();
            if (threshold > 0 && (w0 > threshold || h0 > threshold)) {
                Optional<Dimensions.Size> scaled = SizePlan.scaled(w0, h0, threshold);
                if (scaled.isEmpty()) {
                    // within a pixel of the threshold box: no copy at all, not even a rotated one
                    return null;
                }
                DecodedImage resized = images.add(original.region(
                        0,
                        0,
                        original.width(),
                        original.height(),
                        scaled.get().width(),
                        scaled.get().height()));
                DecodedImage turned = upright(resized, orientation, images);
                return encodeMain(
                        turned, SizePlan.scaledName(sourceName, realType, outputType), outputType, turned != resized);
            }
            if (!outputType.equals(realType)) {
                return encodeMain(
                        upright, SizePlan.convertedName(sourceName, outputType), outputType, upright != original);
            }
            if (upright != original) {
                return encodeMain(upright, SizePlan.rotatedName(sourceName, realType, outputType), outputType, true);
            }
            return null;
        }

        private MainCopy encodeMain(DecodedImage image, String name, String outputType, boolean rotated)
                throws ImageProcessingException {
            Path file = temporaryFile();
            Encoded encoded = image.encode(file, outputType, QUALITY);
            return new MainCopy(name, file, encoded, rotated);
        }

        private Path temporaryFile() {
            try {
                Path file = Files.createTempFile("quarkpress-media-", ".tmp");
                temporary.add(file);
                return file;
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        /** The sizes still to make, in generation order: those absent by name that the upright image can give. */
        private List<Planned> plan(
                Map<String, ImageSizeDefinition> registered,
                DecodedImage upright,
                String sourceName,
                String realType,
                String outputType) {
            List<Planned> planned = new ArrayList<>();
            for (String name : SizePlan.generationOrder(registered.keySet())) {
                if (media.sizes().containsKey(name)) {
                    continue;
                }
                ImageSizeDefinition size = registered.get(name);
                Dimensions.resize(upright.width(), upright.height(), size.width(), size.height(), size.crop())
                        .ifPresent(region -> planned.add(new Planned(
                                name,
                                region,
                                SizePlan.subSizeName(
                                        sourceName, region.width(), region.height(), realType, outputType))));
            }
            return planned;
        }

        /** Makes and saves each planned size, then the final state. Empty when the attachment went meanwhile. */
        private Optional<MediaSnapshot> sizes(
                List<Planned> planned, DecodedImage upright, String outputType, boolean mainFailed) {
            Map<String, MediaSize> made = new HashMap<>();
            for (Planned size : planned) {
                MediaSize shared = made.get(size.file());
                if (shared == null && present(size.name())) {
                    continue;
                }
                Path file = null;
                Encoded encoded = null;
                if (shared == null) {
                    DecodedImage cut = null;
                    try {
                        Dimensions.Region r = size.region();
                        cut = upright.region(r.srcX(), r.srcY(), r.srcWidth(), r.srcHeight(), r.width(), r.height());
                        file = temporaryFile();
                        encoded = cut.encode(file, outputType, QUALITY);
                    } catch (ImageProcessingException | RuntimeException | OutOfMemoryError failure) {
                        LOG.warnf(
                                "the size %s of attachment %s could not be made: %s",
                                size.name(), post.value(), failure.getMessage());
                        continue;
                    } finally {
                        if (cut != null && cut != upright) {
                            cut.close();
                        }
                    }
                }
                Path stored = file;
                Encoded written = encoded;
                Saved<MediaSize> saved = save(siteId, post, row -> {
                    ObjectNode sizes = sizesOf(row);
                    if (sizes.has(size.name())) {
                        // another run saved it first
                        return null;
                    }
                    Optional<MediaSize> entry =
                            Optional.ofNullable(shared).or(() -> withFile(sizes, size.file(), size.name()));
                    if (entry.isEmpty()) {
                        Optional<Placed> placed =
                                place(folder(row.storageKey), size.file(), stored, written.mimeType(), written.bytes());
                        if (placed.isEmpty()) {
                            return null;
                        }
                        entry = Optional.of(new MediaSize(
                                placed.get().name(),
                                written.width(),
                                written.height(),
                                written.mimeType(),
                                placed.get().bytes()));
                        row.storedBytes += placed.get().bytes();
                    }
                    sizes.set(size.name(), entry(entry.get()));
                    row.sizes = sizes;
                    return entry.get();
                });
                if (saved.gone()) {
                    return Optional.empty();
                }
                if (saved.value() != null) {
                    made.put(size.file(), saved.value());
                }
                if (file != null) {
                    deleteQuietly(file);
                    temporary.remove(file);
                }
            }
            Saved<MediaSnapshot> last = save(siteId, post, row -> {
                ObjectNode sizes = sizesOf(row);
                boolean complete = planned.stream().allMatch(size -> sizes.has(size.name()));
                return finish(siteId, row, complete && !mainFailed ? MediaEntity.DONE : MediaEntity.PARTIAL);
            });
            return last.gone() ? Optional.empty() : Optional.of(last.value());
        }

        /** Whether the row holds a size named {@code name} now (another run may have made it meanwhile). */
        private boolean present(String name) {
            return inTransaction(() -> mediaRows
                    .findMedia(siteId, post)
                    .map(row -> row.sizes != null && row.sizes.has(name))
                    .orElse(false));
        }

        /**
         * The last save of a run from scratch: {@code state} changes the row and decides its final processing state.
         * When another run took the row past pending meanwhile, it is left as that run made it.
         */
        private Optional<MediaSnapshot> last(RowWork<String> state) {
            Saved<MediaSnapshot> saved = save(siteId, post, row -> {
                if (!MediaEntity.PENDING.equals(row.processing)) {
                    return snapshot(siteId, row)
                            .orElseThrow(() -> new IllegalStateException("the attachment post of a media row is gone"));
                }
                return finish(siteId, row, state.apply(row));
            });
            return saved.gone() ? Optional.empty() : Optional.of(saved.value());
        }
    }
}
