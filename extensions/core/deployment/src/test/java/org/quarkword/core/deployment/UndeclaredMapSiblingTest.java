package org.quarkword.core.deployment;

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
            .overrideRuntimeConfigKey("quarkword.x.m.a", "1")
            .overrideRuntimeConfigKey("quarkword.x.other", "1")
            .overrideRuntimeConfigKey("quarkword.x.m", "1")
            .overrideRuntimeConfigKey("quarkword.x.plain.a", "1")
            .overrideRuntimeConfigKey("quarkword.x.m.\"a\".\"b\"", "1")
            .overrideRuntimeConfigKey("quarkword.x.m.\"\"", "1")
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("Unknown QuarkWord settings quarkword.x.m, quarkword.x.m.\"\","
                            + " quarkword.x.m.\"a\".\"b\", quarkword.x.other, quarkword.x.plain.a:"));

    @Test
    void startupFails() {
        // assertException above checks the failure
    }
}
