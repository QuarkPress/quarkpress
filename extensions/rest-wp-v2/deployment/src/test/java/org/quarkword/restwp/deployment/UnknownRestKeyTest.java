package org.quarkword.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class UnknownRestKeyTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.buildOnly(UnknownRestKeyTest.class)
            .overrideConfigKey("quarkword.rest-wp-v2.unknown", "1")
            .assertException(
                    failure -> assertThat(Failures.messages(failure)).contains("quarkword.rest-wp-v2.unknown"));

    @Test
    void theApplicationDoesNotStart() {
        // assertException above checks the failure
    }
}
