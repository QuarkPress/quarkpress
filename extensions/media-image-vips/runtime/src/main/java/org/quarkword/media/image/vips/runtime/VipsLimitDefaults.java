package org.quarkword.media.image.vips.runtime;

import io.quarkus.runtime.configuration.MemorySizeConverter;
import io.smallrye.config.ConfigSourceInterceptor;
import io.smallrye.config.ConfigSourceInterceptorContext;
import io.smallrye.config.ConfigValue;
import java.io.Serial;
import java.util.Optional;
import java.util.function.Function;

/**
 * Keeps libvips's input limits at or above what QuarkWord accepts. When {@code quarkus.libvips.limits.max-input-size}
 * is not configured (it comes from a default), it is the larger of its default and the largest upload QuarkWord
 * accepts: {@code quarkword.media.max-upload-size}, else {@code quarkus.http.limits.max-body-size}.
 * {@code max-input-pixels} likewise rises to {@code quarkword.media.max-image-pixels}. A configured value is never
 * changed; {@link #check} tells when it is too low.
 */
public final class VipsLimitDefaults implements ConfigSourceInterceptor {

    @Serial
    private static final long serialVersionUID = 1L;

    static final String MAX_INPUT_SIZE = "quarkus.libvips.limits.max-input-size";
    static final String MAX_INPUT_PIXELS = "quarkus.libvips.limits.max-input-pixels";
    static final String MAX_UPLOAD_SIZE = "quarkword.media.max-upload-size";
    static final String MAX_BODY_SIZE = "quarkus.http.limits.max-body-size";
    static final String MAX_IMAGE_PIXELS = "quarkword.media.max-image-pixels";

    /** Quarkus's default HTTP body limit. */
    static final String DEFAULT_MAX_BODY_SIZE = "10240K";

    /** The media module's default pixel limit. */
    static final long DEFAULT_MAX_IMAGE_PIXELS = 100_000_000L;

    /** The lowest ordinal of a configuration source a user writes (system properties, environment, files). */
    private static final int CONFIGURED = 100;

    @Override
    public ConfigValue getValue(ConfigSourceInterceptorContext context, String name) {
        ConfigValue value = context.proceed(name);
        boolean size = name.equals(MAX_INPUT_SIZE);
        if ((!size && !name.equals(MAX_INPUT_PIXELS))
                || value == null
                || value.getValue() == null
                || value.getConfigSourceOrdinal() >= CONFIGURED) {
            return value;
        }
        Function<String, Optional<String>> lookup = key -> Optional.ofNullable(context.proceed(key))
                .map(ConfigValue::getValue)
                .filter(text -> !text.isBlank());
        try {
            long own = size
                    ? bytes(value.getValue())
                    : Long.parseLong(value.getValue().strip());
            long quarkWord = size ? acceptedUploadSize(lookup) : maxImagePixels(lookup);
            return own == 0 || own >= quarkWord ? value : value.withValue(Long.toString(quarkWord));
        } catch (IllegalArgumentException invalid) {
            // left to the configuration's own validation, which names the key
            return value;
        }
    }

    /**
     * The largest upload QuarkWord accepts, in bytes: {@code quarkword.media.max-upload-size} when set, else the HTTP
     * body limit.
     */
    static long acceptedUploadSize(Function<String, Optional<String>> lookup) {
        return bytes(lookup.apply(MAX_UPLOAD_SIZE)
                .or(() -> lookup.apply(MAX_BODY_SIZE))
                .orElse(DEFAULT_MAX_BODY_SIZE));
    }

    /** The most pixels an image may have to be decoded by QuarkWord. */
    static long maxImagePixels(Function<String, Optional<String>> lookup) {
        return lookup.apply(MAX_IMAGE_PIXELS)
                .map(text -> Long.parseLong(text.strip()))
                .orElse(DEFAULT_MAX_IMAGE_PIXELS);
    }

    /**
     * Why libvips's limits, as configured, would refuse images QuarkWord accepts: a non-zero limit below the largest
     * upload ({@code acceptedSize} bytes) or the pixel limit ({@code maxImagePixels}); empty when they do not.
     */
    public static Optional<String> check(
            long maxInputSize, long maxInputPixels, long acceptedSize, long maxImagePixels) {
        if (maxInputSize != 0 && maxInputSize < acceptedSize) {
            return Optional.of(
                    MAX_INPUT_SIZE + " (" + maxInputSize + ") is below the largest upload QuarkWord accepts ("
                            + acceptedSize + "): raise it or set it to 0");
        }
        if (maxInputPixels != 0 && maxInputPixels < maxImagePixels) {
            return Optional.of(MAX_INPUT_PIXELS + " (" + maxInputPixels
                    + ") is below the most pixels QuarkWord decodes (" + maxImagePixels + "): raise it or set it to 0");
        }
        return Optional.empty();
    }

    private static long bytes(String text) {
        return new MemorySizeConverter().convert(text.strip()).asLongValue();
    }
}
