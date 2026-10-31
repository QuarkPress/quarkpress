package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.restwp.runtime.RestWpConfig;

/** The module declares its keys to core, which refuses every {@code quarkpress.*} key no module declared. */
class ConfigDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(ConfigDbTest.class)
            .overrideRuntimeConfigKey("quarkpress.rest-wp-v2.path", "/api")
            .overrideRuntimeConfigKey("quarkpress.rest-wp-v2.cors.origins", "https://a.test,https://b.test");

    @Inject
    RestWpConfig config;

    @Test
    void theDeclaredKeysAreAcceptedAndRead() {
        assertThat(config.path()).isEqualTo("/api");
        assertThat(config.cors().origins()).contains(List.of("https://a.test", "https://b.test"));
    }
}
