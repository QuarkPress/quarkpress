package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Two storage beans with one @Identifier are two backends, not one: the build fails, naming both classes. */
class DuplicateStorageIdentifierTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.buildOnly(SecondOneStorage.class, OneStorage.class)
            .assertException(e -> assertThat(Failures.messages(e))
                    .contains("Several media storage backends are named one"
                            + " (org.quarkpress.media.deployment.OneStorage,"
                            + " org.quarkpress.media.deployment.SecondOneStorage): give each its own @Identifier"));

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
