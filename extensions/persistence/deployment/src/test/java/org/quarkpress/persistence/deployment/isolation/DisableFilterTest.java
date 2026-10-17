package org.quarkpress.persistence.deployment.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.persistence.deployment.DbApp;
import org.quarkpress.persistence.deployment.Failures;

class DisableFilterTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.buildOnly(Attempts.class, Attempts.FilterOff.class)
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains(Attempts.FilterOff.class.getName())
                    .contains("disables the site filter"));

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
