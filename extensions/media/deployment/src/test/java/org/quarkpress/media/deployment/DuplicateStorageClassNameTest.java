package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Two storage beans without @Identifier whose classes share a simple name fail the build, naming both classes. */
class DuplicateStorageClassNameTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.buildOnly(
                    MemoryStorageBean.class, org.quarkpress.media.deployment.other.MemoryStorageBean.class)
            .assertException(e -> assertThat(Failures.messages(e))
                    .contains("Several media storage backends are named MemoryStorageBean"
                            + " (org.quarkpress.media.deployment.MemoryStorageBean,"
                            + " org.quarkpress.media.deployment.other.MemoryStorageBean): give each its own"
                            + " @Identifier"));

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
