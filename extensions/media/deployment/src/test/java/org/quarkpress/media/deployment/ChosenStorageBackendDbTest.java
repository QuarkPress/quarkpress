package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.media.runtime.ActiveStorage;

/** With two storage backends, quarkpress.media.storage picks the one in use. */
class ChosenStorageBackendDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.withoutStorage(
                    ChosenStorageBackendDbTest.class, OneStorage.class, TwoStorage.class)
            .overrideConfigKey("quarkpress.media.storage", "two");

    @Inject
    ActiveStorage active;

    @Test
    void theChosenBackendIsActive() {
        assertThat(active.storage()).isInstanceOf(TwoStorage.class);
        assertThat(active.name()).isEqualTo("two");
    }
}
