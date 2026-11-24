package org.quarkword.core.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** A plain map segment holds no [, ] or *, so the map and list key forms stay apart; a quoted one may. */
class MapSegmentCharactersTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MapConfigKeys.app(MapSegmentCharactersTest.class)
            .overrideRuntimeConfigKey("quarkword.x.m.a[0]", "1")
            .overrideRuntimeConfigKey("quarkword.x.m.b]", "1")
            .overrideRuntimeConfigKey("quarkword.x.m.*", "1")
            .overrideRuntimeConfigKey("quarkword.x.m.\"c[0]*\"", "1")
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("Unknown QuarkWord settings quarkword.x.m.*, quarkword.x.m.a[0], quarkword.x.m.b]:"));

    @Test
    void startupFails() {
        // assertException above checks the failure
    }
}
