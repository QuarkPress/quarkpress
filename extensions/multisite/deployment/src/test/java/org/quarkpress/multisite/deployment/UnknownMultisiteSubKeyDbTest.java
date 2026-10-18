package org.quarkpress.multisite.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** A declared key is not a prefix: a sub-key of a plain setting fails the start. */
class UnknownMultisiteSubKeyDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MultisiteApp.with(UnknownMultisiteSubKeyDbTest.class)
            .overrideRuntimeConfigKey("quarkpress.multisite.path-segments.x", "2")
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("Unknown QuarkPress setting quarkpress.multisite.path-segments.x"));

    @Test
    void startupFails() {
        // assertException above checks the failure
    }
}
