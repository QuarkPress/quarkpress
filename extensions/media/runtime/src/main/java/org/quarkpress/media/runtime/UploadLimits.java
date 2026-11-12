package org.quarkpress.media.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.configuration.MemorySize;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.quarkpress.core.SiteContext;
import org.quarkpress.media.MediaException;
import org.quarkpress.persistence.NetworkOptions;
import org.quarkpress.security.runtime.InstallMode;
import org.quarkpress.security.runtime.PhpTruth;

/**
 * WordPress's network upload limits, as its REST upload checks them on a network: the space left on the site, the
 * per-file limit and the site's quota, in that order, the first failing test answering.
 *
 * <p>The limits apply when {@code quarkpress.media.network-limits.enabled} is true, or when it is unset and the network
 * option {@code upload_space_check_disabled} is false as PHP reads it (a network QuarkPress creates sets it to
 * {@code "1"}, as WordPress's network setup does, so the limits start off). The site's used space is the sum of the
 * stored sizes of its attachments' files.
 */
@ApplicationScoped
public class UploadLimits {

    static final long KB_IN_BYTES = 1024;
    static final long MB_IN_BYTES = 1_048_576;

    /** The per-file limit in KB when the network has none. */
    static final String DEFAULT_MAX_K = "1500";

    /** The quota in MB when neither the site nor the network has a numeric one. */
    static final long DEFAULT_SPACE_MB = 100;

    private static final BigDecimal MB = BigDecimal.valueOf(MB_IN_BYTES);
    private static final BigDecimal MAX_BYTES = BigDecimal.valueOf(Long.MAX_VALUE);
    private static final BigDecimal MIN_BYTES = BigDecimal.valueOf(Long.MIN_VALUE);

    /** A site's quota (negative as configured) and the space its files take, in bytes. */
    public record Space(long allowedBytes, long usedBytes) {

        /** The bytes left: 0 when the files take the whole quota or more, or when the quota is negative. */
        long available() {
            return usedBytes >= allowedBytes ? 0 : allowedBytes - usedBytes;
        }
    }

    @Inject
    InstallMode installMode;

    @Inject
    MediaConfig config;

    @Inject
    NetworkOptions network;

    @Inject
    SiteContext site;

    @Inject
    MediaRepository mediaRows;

    /** The HTTP body limit, Quarkus's default when no HTTP extension sets one. */
    @ConfigProperty(name = "quarkus.http.limits.max-body-size", defaultValue = "10240K")
    MemorySize maxBodySize;

    /**
     * The largest upload the current site tells its editor it takes, as WordPress's {@code wp_max_upload_size}
     * answers: the smaller of the configured maximum (when set) and the HTTP body limit, and on a network also the
     * per-file limit {@code fileupload_maxk} in KB (read as a whole number, as WordPress's multisite filter casts it,
     * and applied whether or not the network's checks are on) and, with the checks on, the space left on the site
     * (its quota less the stored sizes of its files, never below 0).
     */
    public long maxUploadSize() {
        if (!installMode.multisite()) {
            return smallest(config.maxUploadSize(), maxBodySize, Optional.empty());
        }
        long networkCap = ((long) leadingNumber(maxK(network.find("fileupload_maxk")))) * KB_IN_BYTES;
        if (checksOn(config.networkLimits().enabled(), network.find("upload_space_check_disabled"))) {
            long allowed = allowedBytes(site.options().get("blog_upload_space"), network.find("blog_upload_space"));
            long used = mediaRows.storedBytes(site.siteId());
            networkCap = Math.min(networkCap, new Space(allowed, used).available());
        }
        return smallest(config.maxUploadSize(), maxBodySize, Optional.of(networkCap));
    }

    /** The smallest of the configured maximum (when set), the HTTP body limit and the network's cap (when any). */
    public static long smallest(Optional<MemorySize> configured, MemorySize bodyLimit, Optional<Long> networkCap) {
        long limit = bodyLimit.asLongValue();
        if (configured.isPresent()) {
            limit = Math.min(limit, configured.get().asLongValue());
        }
        if (networkCap.isPresent()) {
            limit = Math.min(limit, networkCap.get());
        }
        return limit;
    }

    /**
     * The refusal of an upload of {@code size} bytes to the current site by the network's limits; empty on a single
     * site, when the limits are off, or when the upload passes them.
     */
    public Optional<MediaException> forSite(long size) {
        if (!installMode.multisite()) {
            return Optional.empty();
        }
        if (!checksOn(config.networkLimits().enabled(), network.find("upload_space_check_disabled"))) {
            return Optional.empty();
        }
        long allowed = allowedBytes(site.options().get("blog_upload_space"), network.find("blog_upload_space"));
        long used = mediaRows.storedBytes(site.siteId());
        return check(size, maxK(network.find("fileupload_maxk")), new Space(allowed, used));
    }

    /**
     * WordPress's three tests, the first failing one answering: not enough space left for the file, the file larger
     * than {@code fileuploadMaxK} KB (exactly the limit passes), the site over its quota (reachable only by an empty
     * file, since any other file already fails the first test).
     *
     * @param fileuploadMaxK the network's per-file limit as stored; the message prints it so, and the comparison reads
     *     its leading number as PHP arithmetic does (a value with none refuses every non-empty file, where WordPress
     *     fails with a PHP type error)
     */
    public static Optional<MediaException> check(long size, String fileuploadMaxK, Space space) {
        long left = space.available();
        if (left < size) {
            double needed = (double) (size - left) / KB_IN_BYTES;
            return Optional.of(new MediaException(
                    "rest_upload_limited_space",
                    "Not enough space to upload. " + numberFormat(needed) + " KB needed."));
        }
        if (size > leadingNumber(fileuploadMaxK) * KB_IN_BYTES) {
            return Optional.of(new MediaException(
                    "rest_upload_file_too_big",
                    "This file is too big. Files must be less than " + fileuploadMaxK + " KB in size."));
        }
        // over quota: allowed - used below 0, compared without overflow
        if (space.usedBytes() > space.allowedBytes()) {
            return Optional.of(new MediaException(
                    "rest_upload_user_quota_exceeded",
                    "You have used your space quota. Please delete files before uploading."));
        }
        return Optional.empty();
    }

    /**
     * PHP's {@code number_format} with no decimals for a value that is never negative: rounded half up on the
     * shortest decimal form of the value, thousands grouped with {@code ,}.
     */
    public static String numberFormat(double value) {
        String digits =
                BigDecimal.valueOf(value).setScale(0, RoundingMode.HALF_UP).toPlainString();
        StringBuilder grouped = new StringBuilder(digits.length() + digits.length() / 3);
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                grouped.append(',');
            }
            grouped.append(digits.charAt(i));
        }
        return grouped.toString();
    }

    /**
     * Whether the network limits apply: the configuration when set, else the network option
     * {@code upload_space_check_disabled} false as PHP reads it (absent counts as false).
     */
    static boolean checksOn(Optional<Boolean> configured, Optional<JsonNode> disabled) {
        return configured.orElseGet(() -> !PhpTruth.truthy(disabled.orElse(null)));
    }

    /**
     * The site's quota in bytes: the site's option when numeric, else the network's when numeric, else 100 MB; a
     * negative quota stays negative (it leaves no space, and the site is over it) and a fraction of a byte is dropped.
     */
    static long allowedBytes(Optional<JsonNode> siteOption, Optional<JsonNode> networkOption) {
        BigDecimal megabytes =
                numeric(siteOption).or(() -> numeric(networkOption)).orElse(BigDecimal.valueOf(DEFAULT_SPACE_MB));
        BigDecimal bytes = megabytes.multiply(MB).setScale(0, RoundingMode.FLOOR);
        if (bytes.compareTo(MAX_BYTES) > 0) {
            return Long.MAX_VALUE;
        }
        return bytes.compareTo(MIN_BYTES) < 0 ? Long.MIN_VALUE : bytes.longValueExact();
    }

    /** The network's per-file limit as PHP prints it; {@code 1500} when unset or not a text, number or boolean. */
    static String maxK(Optional<JsonNode> option) {
        return option.map(PhpOptionStrings::of).orElse(DEFAULT_MAX_K);
    }

    /** A JSON number, or a text PHP's {@code is_numeric} accepts, as a decimal; empty for anything else. */
    private static Optional<BigDecimal> numeric(Optional<JsonNode> option) {
        if (option.isEmpty()) {
            return Optional.empty();
        }
        JsonNode value = option.get();
        if (value.isNumber()) {
            return Optional.of(value.decimalValue());
        }
        if (value.isTextual()) {
            Object number = ExifNumbers.isNumeric(value.textValue()) ? ExifNumbers.numeric(value.textValue()) : null;
            if (number instanceof Long whole) {
                return Optional.of(BigDecimal.valueOf(whole));
            }
            if (number instanceof Double fraction && Double.isFinite(fraction)) {
                return Optional.of(BigDecimal.valueOf(fraction));
            }
        }
        return Optional.empty();
    }

    /**
     * The number PHP's arithmetic reads from {@code text}: its leading numeric part after leading whitespace (an
     * optional sign, digits with an optional fraction, an optional exponent), 0 when it has none.
     */
    static double leadingNumber(String text) {
        int length = Math.min(text.length(), ExifNumbers.MAX_TEXT);
        int start = 0;
        while (start < length && isPhpWhitespace(text.charAt(start))) {
            start++;
        }
        int i = start;
        if (i < length && (text.charAt(i) == '+' || text.charAt(i) == '-')) {
            i++;
        }
        int digits = 0;
        while (i < length && isDigit(text.charAt(i))) {
            i++;
            digits++;
        }
        if (i < length && text.charAt(i) == '.') {
            int fraction = i + 1;
            while (fraction < length && isDigit(text.charAt(fraction))) {
                fraction++;
            }
            if (digits > 0 || fraction > i + 1) {
                digits += fraction - i - 1;
                i = fraction;
            }
        }
        if (digits == 0) {
            return 0;
        }
        if (i < length && (text.charAt(i) == 'e' || text.charAt(i) == 'E')) {
            int exponent = i + 1;
            if (exponent < length && (text.charAt(exponent) == '+' || text.charAt(exponent) == '-')) {
                exponent++;
            }
            int exponentStart = exponent;
            while (exponent < length && isDigit(text.charAt(exponent))) {
                exponent++;
            }
            if (exponent > exponentStart) {
                i = exponent;
            }
        }
        return Double.parseDouble(text.substring(start, i));
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isPhpWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\f';
    }
}
