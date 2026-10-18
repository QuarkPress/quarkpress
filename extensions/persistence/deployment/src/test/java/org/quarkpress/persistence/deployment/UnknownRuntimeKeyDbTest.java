package org.quarkpress.persistence.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** A mistyped run-time quarkpress.* key fails the start instead of keeping the default. */
class UnknownRuntimeKeyDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.with(UnknownRuntimeKeyDbTest.class)
            .overrideRuntimeConfigKey("quarkpress.main-site-idd", "2")
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("Unknown QuarkPress setting quarkpress.main-site-idd"));

    @Test
    void startupFails() {
        // assertException above checks the failure
    }
}
