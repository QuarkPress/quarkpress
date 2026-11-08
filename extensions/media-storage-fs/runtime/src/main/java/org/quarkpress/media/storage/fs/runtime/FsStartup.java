package org.quarkpress.media.storage.fs.runtime;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Instant;
import org.jboss.logging.Logger;

/**
 * Checks at start-up that the filesystem backend has its directory and, when the application serves the files, an
 * uploads path below the site's root, and removes the temporary files an interrupted write left behind. Present only
 * when the filesystem backend is the one in use.
 */
public class FsStartup {

    private static final Logger LOG = Logger.getLogger(FsStartup.class);

    @Inject
    FsConfig config;

    @Inject
    @Identifier("fs")
    FsMediaStorage storage;

    void start(@Observes StartupEvent event) {
        if (config.directory().isEmpty()) {
            throw new IllegalStateException(FsMediaStorage.DIRECTORY_REQUIRED);
        }
        if (config.baseUrl().isEmpty()) {
            UploadsRoute.requireServablePath(config.path());
        }
        int removed = storage.sweep(Instant.now());
        if (removed > 0) {
            LOG.infof("Removed %d stale temporary files from %s", removed, storage.directory());
        }
    }
}
