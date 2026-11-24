package org.quarkword.media.storage.s3.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.media.runtime.ActiveStorage;
import org.quarkword.media.storage.fs.runtime.FsMediaStorage;
import org.quarkword.media.storage.s3.runtime.S3Startup;

/**
 * Both backends in one application, the filesystem one chosen: the S3 backend needs neither its own settings nor the
 * client's. Without a region the S3 client cannot be built, so a start without one shows that it never was.
 */
class FsChosenOverS3DbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = S3App.app(FsChosenOverS3DbTest.class)
            .overrideConfigKey("quarkword.media.storage", "fs")
            .overrideConfigKey("quarkus.s3.devservices.enabled", "false")
            .overrideRuntimeConfigKey(
                    "quarkword.media.fs.directory",
                    S3App.directory("fs-chosen-over-s3").toString());

    @Inject
    ActiveStorage active;

    @Test
    void s3NeedsNothingWhenFsIsChosen() {
        assertThat(active.name()).isEqualTo("fs");
        assertThat(active.storage()).isInstanceOf(FsMediaStorage.class);
        assertThat(Arc.container().instance(S3Startup.class).isAvailable()).isFalse();
    }
}
