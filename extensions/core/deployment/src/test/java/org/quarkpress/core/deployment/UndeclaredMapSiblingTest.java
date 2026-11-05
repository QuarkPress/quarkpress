package org.quarkpress.core.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Declaring a map opens nothing beside it: an undeclared sibling, the map's own name, two quoted segments and an empty
 * quoted segment still fail the start.
 */
class UndeclaredMapSiblingTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MapConfigKeys.app(UndeclaredMapSiblingTest.class)
            .overrideRuntimeConfigKey("quarkpress.x.m.a", "1")
            .overrideRuntimeConfigKey("quarkpress.x.other", "1")
            .overrideRuntimeConfigKey("quarkpress.x.m", "1")
            .overrideRuntimeConfigKey("quarkpress.x.plain.a", "1")
            .overrideRuntimeConfigKey("quarkpress.x.m.\"a\".\"b\"", "1")
            .overrideRuntimeConfigKey("quarkpress.x.m.\"\"", "1")
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("Unknown QuarkPress settings quarkpress.x.m, quarkpress.x.m.\"\","
                            + " quarkpress.x.m.\"a\".\"b\", quarkpress.x.other, quarkpress.x.plain.a:"));

    @Test
    void startupFails() {
        // assertException above checks the failure
    }
}
