package org.quarkpress.it.medias3;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;
import java.util.UUID;

/**
 * A new key prefix ({@code it-<uuid>}) for each start of the application under test, so that the stored file names
 * never depend on an earlier run against the same bucket. In {@code @QuarkusTest} the setting overrides the
 * application's configuration; in {@code @QuarkusIntegrationTest} Quarkus passes it to the launched jar or native
 * executable as a system property.
 */
public class S3PrefixResource implements QuarkusTestResourceLifecycleManager {

    @Override
    public Map<String, String> start() {
        return Map.of("quarkpress.media.s3.key-prefix", "it-" + UUID.randomUUID());
    }

    @Override
    public void stop() {
        // the objects go with moto's container
    }
}
