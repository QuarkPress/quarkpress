package org.quarkword.front.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Core refuses a {@code quarkword.front.*} key this module does not declare. */
class FrontConfigKeysDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = FrontApp.with(FrontConfigKeysDbTest.class)
            .overrideRuntimeConfigKey("quarkword.front.nope", "1")
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("Unknown QuarkWord setting quarkword.front.nope")
                    .contains("quarkword.front.cache-control"));

    @Test
    void theApplicationDoesNotStart() {
        // assertException above checks the failure
    }
}
