package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class UnknownSecurityKeyDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SecurityApp.with(UnknownSecurityKeyDbTest.class)
            .overrideRuntimeConfigKey("quarkpress.security.initial-super-admin", "x")
            .assertException(failure ->
                    assertThat(Failures.messages(failure)).contains("quarkpress.security.initial-super-admin"));

    @Test
    void theApplicationDoesNotStart() {
        // assertException above checks the failure
    }
}
