package org.quarkpress.media.runtime;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.quarkus.runtime.configuration.MemorySize;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Media settings read when the application starts. */
@ConfigMapping(prefix = "quarkpress.media")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface MediaConfig {

    /**
     * The output type of each image type WordPress does not write sub-sizes in, unless configured otherwise: HEIC and
     * HEIF images (and their sequences) get JPEG sub-sizes.
     */
    Map<String, String> DEFAULT_OUTPUT_FORMATS = Map.of(
            "image/heic", "image/jpeg",
            "image/heif", "image/jpeg",
            "image/heic-sequence", "image/jpeg",
            "image/heif-sequence", "image/jpeg");

    /** The largest file a site accepts; unset, no limit applies beyond the network's own upload limit. */
    Optional<MemorySize> maxUploadSize();

    /** The network's upload limits. */
    NetworkLimits networkLimits();

    /**
     * Upload types added to WordPress's list, as extension pattern (such as {@code svg} or {@code jpg|jpeg}) to MIME
     * type; a pattern holding a dot is quoted ({@code quarkpress.media.allowed-types."tar.gz"}). They come after
     * WordPress's types in lookup order.
     */
    Map<String, String> allowedTypes();

    /** File extensions no site may upload, whatever the allowed types say. */
    Optional<List<String>> deniedExtensions();

    /** Whether an image the processor cannot decode is still stored, without generated sizes. */
    @WithDefault("false")
    boolean acceptUnprocessableImages();

    /** When the generated sizes are made: during the upload ({@code sync}) or after it ({@code async}). */
    @WithDefault("sync")
    ProcessingMode processing();

    /**
     * The longest side above which an uploaded image is scaled down for use in place of the original, which is kept;
     * {@code 0} turns the scaling off.
     */
    @WithDefault("2560")
    int bigImageThreshold();

    /** The most pixels (width times height, read from the file's header) an image may have to be decoded at all. */
    @WithDefault("100000000")
    long maxImagePixels();

    /**
     * How many images are decoded at once; further uploads wait for their turn. Unset, half the available processors,
     * at least one.
     */
    Optional<Integer> maxConcurrentDecodes();

    /**
     * The type sub-sizes are written in, by source MIME type, on top of {@link #DEFAULT_OUTPUT_FORMATS}; see
     * {@link #effectiveOutputFormats()}.
     */
    Map<String, String> outputFormats();

    /** {@link #DEFAULT_OUTPUT_FORMATS} with {@link #outputFormats()} applied over it. */
    default Map<String, String> effectiveOutputFormats() {
        Map<String, String> formats = new LinkedHashMap<>(DEFAULT_OUTPUT_FORMATS);
        formats.putAll(outputFormats());
        return formats;
    }

    /** The network's upload limits. */
    interface NetworkLimits {

        /** Whether the network's upload limits apply: unset, they follow the network's own option. */
        Optional<Boolean> enabled();
    }

    /** When generated image sizes are made. */
    enum ProcessingMode {
        /** During the upload, before it returns. */
        SYNC,
        /** After the upload returns, in the background. */
        ASYNC
    }
}
