package org.quarkpress.media.storage.s3.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.media.runtime.ActiveStorage;
import org.quarkpress.media.storage.s3.runtime.S3MediaStorage;
import org.quarkpress.media.storage.s3.runtime.S3Startup;

/** The S3 backend chosen and given a bucket is the media module's storage, with its start-up check. */
class ActiveS3DbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = S3App.active(ActiveS3DbTest.class);

    @Inject
    ActiveStorage active;

    @Test
    void s3IsTheActiveStorage() {
        assertThat(active.name()).isEqualTo("s3");
        assertThat(active.storage()).isInstanceOf(S3MediaStorage.class);
        S3MediaStorage storage = (S3MediaStorage) active.storage();
        assertThat(storage.settings().bucket()).isEqualTo(S3App.BUCKET);
        assertThat(storage.settings().keyPrefix()).startsWith("t-");
        assertThat(Arc.container().instance(S3Startup.class).isAvailable()).isTrue();
    }
}
