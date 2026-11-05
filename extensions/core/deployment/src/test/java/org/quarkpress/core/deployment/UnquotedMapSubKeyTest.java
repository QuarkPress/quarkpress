package org.quarkpress.core.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** A map accepts one further segment only: an unquoted dotted map key is two segments and fails the start. */
class UnquotedMapSubKeyTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MapConfigKeys.app(UnquotedMapSubKeyTest.class)
            .overrideRuntimeConfigKey("quarkpress.x.m.a.b", "1")
            .assertException(failure ->
                    assertThat(Failures.messages(failure)).contains("Unknown QuarkPress setting quarkpress.x.m.a.b:"));

    @Test
    void startupFails() {
        // assertException above checks the failure
    }
}
