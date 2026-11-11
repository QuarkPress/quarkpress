package org.quarkpress.media.storage.s3.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.media.runtime.ActiveStorage;
import org.quarkpress.media.storage.s3.runtime.S3Startup;

/** When another backend is in use, the S3 backend needs no bucket and no client settings. */
class InactiveS3DbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = S3App.app(InactiveS3DbTest.class, OneStorage.class)
            .overrideConfigKey("quarkpress.media.storage", "one")
            .overrideConfigKey("quarkus.s3.devservices.enabled", "false");

    @Inject
    ActiveStorage active;

    @Test
    void inactiveS3NeedsNoBucket() {
        assertThat(active.name()).isEqualTo("one");
        assertThat(active.storage()).isInstanceOf(OneStorage.class);
        assertThat(Arc.container().instance(S3Startup.class).isAvailable()).isFalse();
    }
}
