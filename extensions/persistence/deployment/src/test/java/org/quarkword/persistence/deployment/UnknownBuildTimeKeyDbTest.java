package org.quarkword.persistence.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** A mistyped build-time quarkword.* key fails the start instead of being ignored. */
class UnknownBuildTimeKeyDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.with(UnknownBuildTimeKeyDbTest.class)
            .overrideConfigKey("quarkword.persistence.migraton-vendor", "postgresql")
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("Unknown QuarkWord setting quarkword.persistence.migraton-vendor"));

    @Test
    void startupFails() {
        // assertException above checks the failure
    }
}
