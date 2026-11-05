package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** A storage choice naming no present backend fails the build, naming those present. */
class UnknownStorageChoiceTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.buildOnly(OneStorage.class, TwoStorage.class)
            .overrideConfigKey("quarkpress.media.storage", "three")
            .assertException(e -> assertThat(Failures.messages(e))
                    .contains("quarkpress.media.storage names three, which is not one of the media storage backends"
                            + " present (one, two)"));

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
