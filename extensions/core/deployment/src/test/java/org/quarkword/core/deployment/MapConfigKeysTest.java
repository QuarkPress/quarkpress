package org.quarkword.core.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** A declared map accepts one further key segment, quoted (it may then hold dots) or plain. */
class MapConfigKeysTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MapConfigKeys.app(MapConfigKeysTest.class)
            .overrideRuntimeConfigKey("quarkword.x.m.\"a.b\"", "1")
            .overrideRuntimeConfigKey("quarkword.x.m.a", "2")
            .overrideRuntimeConfigKey("quarkword.x.plain", "3");

    @Test
    void quotedAndPlainMapKeysStart() {
        assertThat(ConfigProvider.getConfig().getValue("quarkword.x.m.a", String.class))
                .isEqualTo("2");
        assertThat(ConfigProvider.getConfig().getValue("quarkword.x.m.\"a.b\"", String.class))
                .isEqualTo("1");
    }
}
