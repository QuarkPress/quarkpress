package org.quarkpress.core.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** A plain map segment holds no [, ] or *, so the map and list key forms stay apart; a quoted one may. */
class MapSegmentCharactersTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MapConfigKeys.app(MapSegmentCharactersTest.class)
            .overrideRuntimeConfigKey("quarkpress.x.m.a[0]", "1")
            .overrideRuntimeConfigKey("quarkpress.x.m.b]", "1")
            .overrideRuntimeConfigKey("quarkpress.x.m.*", "1")
            .overrideRuntimeConfigKey("quarkpress.x.m.\"c[0]*\"", "1")
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("Unknown QuarkPress settings quarkpress.x.m.*, quarkpress.x.m.a[0], quarkpress.x.m.b]:"));

    @Test
    void startupFails() {
        // assertException above checks the failure
    }
}
