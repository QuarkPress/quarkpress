package org.quarkpress.media.storage.fs.runtime;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.nio.file.Path;
import java.util.Optional;

/** Settings of the filesystem storage backend, read when the application starts. */
@ConfigMapping(prefix = "quarkpress.media.fs")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface FsConfig {

    /** The directory that holds the stored files; required when this backend is the one in use. */
    Optional<Path> directory();

    /**
     * The URL the files are published under, such as a CDN's; unset, a file's URL is the site's address followed by
     * {@link #path()}.
     */
    Optional<String> baseUrl();

    /** The URL path the application serves the stored files under. */
    @WithDefault("/wp-content/uploads")
    String path();
}
