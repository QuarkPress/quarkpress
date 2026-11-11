package org.quarkpress.media.storage.s3.runtime;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.Optional;

/**
 * Settings of the S3 storage backend, read when the application starts. The client's own settings (endpoint, region,
 * credentials, path-style access) are Quarkus's {@code quarkus.s3.*}.
 */
@ConfigMapping(prefix = "quarkpress.media.s3")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface S3Config {

    /** The bucket that holds the stored files; required when this backend is the one in use. */
    Optional<String> bucket();

    /**
     * A key prefix every stored file's object key starts with, such as {@code media} for {@code media/2026/11/a.jpg};
     * without a leading or trailing {@code /}. Unset, the keys start at the bucket's root.
     */
    Optional<String> keyPrefix();

    /**
     * The URL the files are published under, such as a CDN's or the bucket's website; a file's URL is this URL, a
     * {@code /} and its key. Unset, the URL is the object's URL in the bucket, or a presigned one when
     * {@link #presignedUrlTtl()} is set.
     */
    Optional<String> publicBaseUrl();

    /**
     * When set (more than 0 and at most 7 days), files without a {@link #publicBaseUrl()} get presigned URLs valid for
     * this long, for a private bucket. Such URLs expire, also where content saved them.
     */
    Optional<Duration> presignedUrlTtl();

    /**
     * Whether a new file is written with a conditional request ({@code If-None-Match: *}), so that it never replaces a
     * file of the same name a concurrent writer stored. Turn it off only for a store that does not support it.
     */
    @WithDefault("true")
    boolean conditionalWrites();
}
