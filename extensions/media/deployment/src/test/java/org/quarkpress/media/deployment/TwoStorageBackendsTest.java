package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Two storage backends and no choice fail the build, naming both. */
class TwoStorageBackendsTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.buildOnly(TwoStorage.class, OneStorage.class)
            .assertException(e -> assertThat(Failures.messages(e))
                    .contains("Several media storage backends are present (one, two): choose one with"
                            + " quarkpress.media.storage"));

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
