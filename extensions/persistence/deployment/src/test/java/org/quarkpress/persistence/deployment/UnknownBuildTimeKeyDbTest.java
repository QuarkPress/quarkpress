package org.quarkpress.persistence.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** A mistyped build-time quarkpress.* key fails the start instead of being ignored. */
class UnknownBuildTimeKeyDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.with(UnknownBuildTimeKeyDbTest.class)
            .overrideConfigKey("quarkpress.persistence.migraton-vendor", "postgresql")
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("Unknown QuarkPress setting quarkpress.persistence.migraton-vendor"));

    @Test
    void startupFails() {
        // assertException above checks the failure
    }
}
