package org.quarkpress.media.storage.s3.runtime;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Checks at start-up that the S3 backend has a bucket and valid settings, without a request to the store. Present
 * only when the S3 backend is the one in use; it reads the settings alone, so the S3 client is not built by it.
 */
public class S3Startup {

    @Inject
    S3Config config;

    void start(@Observes StartupEvent event) {
        S3Settings.of(config);
    }
}
