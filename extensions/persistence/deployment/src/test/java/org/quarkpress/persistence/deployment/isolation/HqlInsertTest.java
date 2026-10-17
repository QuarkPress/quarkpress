package org.quarkpress.persistence.deployment.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.persistence.deployment.DbApp;
import org.quarkpress.persistence.deployment.Failures;

class HqlInsertTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.buildOnly(Attempts.class, Attempts.HqlInsert.class)
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains(Attempts.HqlInsert.class.getName())
                    .contains("inserts into site-scoped entity OptionEntity"));

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
