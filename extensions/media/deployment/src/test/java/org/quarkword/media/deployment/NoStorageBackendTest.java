package org.quarkword.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Media without any storage backend fails the build. */
class NoStorageBackendTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.buildOnly()
            .assertException(e -> assertThat(Failures.messages(e))
                    .contains("QuarkWord media needs a storage backend: add quarkword-media-storage-fs or"
                            + " quarkword-media-storage-s3, or provide a MediaStorage bean"));

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
