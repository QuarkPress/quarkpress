package org.quarkword.persistence.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** A mistyped run-time quarkword.* key fails the start instead of keeping the default. */
class UnknownRuntimeKeyDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.with(UnknownRuntimeKeyDbTest.class)
            .overrideRuntimeConfigKey("quarkword.main-site-idd", "2")
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("Unknown QuarkWord setting quarkword.main-site-idd"));

    @Test
    void startupFails() {
        // assertException above checks the failure
    }
}
