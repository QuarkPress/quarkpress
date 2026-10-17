package org.quarkpress.persistence.deployment.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.persistence.deployment.DbApp;
import org.quarkpress.persistence.deployment.Failures;

class OpenStatelessSessionTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.buildOnly(Attempts.class, Attempts.StatelessOpener.class)
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains(Attempts.StatelessOpener.class.getName())
                    .contains("opens a StatelessSession"));

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
