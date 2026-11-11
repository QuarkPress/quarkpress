package org.quarkpress.media.storage.s3.runtime;

import java.time.Duration;
import java.util.Optional;
import org.quarkpress.media.StoredKey;

/**
 * The checked settings of the S3 storage backend.
 *
 * @param bucket the bucket that holds the files
 * @param keyPrefix the prefix of every object key, without a leading or trailing {@code /}; empty when there is none
 * @param publicBaseUrl the URL the files are published under, without trailing {@code /}
 * @param presignedUrlTtl how long presigned URLs are valid, when the files get presigned URLs
 * @param conditionalWrites whether new files are written with {@code If-None-Match: *}
 */
public record S3Settings(
        String bucket,
        String keyPrefix,
        Optional<String> publicBaseUrl,
        Optional<Duration> presignedUrlTtl,
        boolean conditionalWrites) {

    /** The longest validity S3 allows for a presigned URL. */
    static final Duration MAX_PRESIGNED_TTL = Duration.ofDays(7);

    static final String BUCKET_REQUIRED = "quarkpress.media.s3.bucket is required by quarkpress-media-storage-s3";

    /**
     * Checks {@code config}.
     *
     * @throws IllegalStateException naming the setting that is missing or invalid
     */
    public static S3Settings of(S3Config config) {
        String bucket = config.bucket()
                .filter(name -> !name.isBlank())
                .orElseThrow(() -> new IllegalStateException(BUCKET_REQUIRED));
        String keyPrefix = config.keyPrefix().map(S3Settings::keyPrefix).orElse("");
        Optional<Duration> ttl = config.presignedUrlTtl();
        ttl.ifPresent(S3Settings::checkTtl);
        Optional<String> publicBaseUrl = config.publicBaseUrl().map(S3Settings::withoutTrailingSlashes);
        return new S3Settings(bucket, keyPrefix, publicBaseUrl, ttl, config.conditionalWrites());
    }

    private static String keyPrefix(String prefix) {
        if (prefix.startsWith("/") || prefix.endsWith("/")) {
            throw new IllegalStateException("quarkpress.media.s3.key-prefix has no leading or trailing /: " + prefix);
        }
        try {
            return StoredKey.require(prefix);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException(
                    "quarkpress.media.s3.key-prefix is not a valid key prefix: " + invalid.getMessage(), invalid);
        }
    }

    private static void checkTtl(Duration ttl) {
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(MAX_PRESIGNED_TTL) > 0) {
            throw new IllegalStateException(
                    "quarkpress.media.s3.presigned-url-ttl is longer than 0 and at most 7 days (P7D), not " + ttl);
        }
    }

    private static String withoutTrailingSlashes(String url) {
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }
}
