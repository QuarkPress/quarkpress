package org.quarkpress.media.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteFeatureDisabledException;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaFeature;
import org.quarkpress.media.MediaSize;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.MediaStorage;
import org.quarkpress.media.Processing;
import org.quarkpress.media.Upload;
import org.quarkpress.persistence.runtime.QuarkPressRuntimeConfig;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;

/** The media library of the current site, on the posts module's attachments and the media rows. */
@ApplicationScoped
public class JpaMedia implements Media {

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

    @Override
    public MediaSnapshot store(Upload upload) {
        return pipeline.store(upload);
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
        return new MediaSnapshot(
                post,
                row.mimeType,
                row.storageKey,
                attachedFile,
                Optional.ofNullable(row.originalKey).map(key -> key.substring(key.lastIndexOf('/') + 1)),
                row.fileSize,
                row.width == null ? OptionalInt.empty() : OptionalInt.of(row.width),
                row.height == null ? OptionalInt.empty() : OptionalInt.of(row.height),
                sizes(row.sizes),
                row.imageMeta instanceof ObjectNode meta ? Optional.of(meta) : Optional.empty(),
                row.extra instanceof ObjectNode extra ? extra : JsonNodeFactory.instance.objectNode(),
                row.altText,
                Processing.valueOf(row.processing.toUpperCase(Locale.ROOT)),
                storage.publicUrl(site, row.storageKey));
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
