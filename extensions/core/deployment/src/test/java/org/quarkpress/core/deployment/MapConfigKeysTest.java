package org.quarkpress.core.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** A declared map accepts one further key segment, quoted (it may then hold dots) or plain. */
class MapConfigKeysTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MapConfigKeys.app(MapConfigKeysTest.class)
            .overrideRuntimeConfigKey("quarkpress.x.m.\"a.b\"", "1")
            .overrideRuntimeConfigKey("quarkpress.x.m.a", "2")
            .overrideRuntimeConfigKey("quarkpress.x.plain", "3");

    @Test
    void quotedAndPlainMapKeysStart() {
        assertThat(ConfigProvider.getConfig().getValue("quarkpress.x.m.a", String.class))
                .isEqualTo("2");
        assertThat(ConfigProvider.getConfig().getValue("quarkpress.x.m.\"a.b\"", String.class))
                .isEqualTo("1");
    }
}
