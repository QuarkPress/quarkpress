package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.FeatureRegistry;
import org.quarkpress.media.runtime.ActiveStorage;

/** The media module declares its per-site feature; its only storage bean is active, named by its class. */
class MediaFeatureDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.with(MediaFeatureDbTest.class);

    @Inject
    FeatureRegistry registry;

    @Inject
    ActiveStorage active;

    @Test
    void theMediaFeatureIsDeclaredAndOnByDefault() {
        assertThat(registry.all()).anySatisfy(d -> {
            assertThat(d.key()).isEqualTo("media");
            assertThat(d.module()).isEqualTo("media");
            assertThat(d.defaultEnabled()).isTrue();
        });
    }

    @Test
    void theOnlyStorageBeanIsActive() {
        assertThat(active.storage()).isInstanceOf(MemoryStorageBean.class);
        assertThat(active.name()).isEqualTo("MemoryStorageBean");
    }
}
