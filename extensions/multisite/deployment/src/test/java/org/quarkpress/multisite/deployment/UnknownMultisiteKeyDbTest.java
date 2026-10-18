package org.quarkpress.multisite.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class UnknownMultisiteKeyDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MultisiteApp.with(UnknownMultisiteKeyDbTest.class)
            .overrideRuntimeConfigKey("quarkpress.multisite.path-segmnts", "2")
            .assertException(
                    failure -> assertThat(Failures.messages(failure)).contains("quarkpress.multisite.path-segmnts"));

    @Test
    void theApplicationDoesNotStart() {
        // assertException above checks the failure
    }
}
