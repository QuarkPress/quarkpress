package org.quarkword.persistence.deployment.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.persistence.deployment.DbApp;
import org.quarkword.persistence.deployment.Failures;

class UnscopedNativeSqlTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.buildOnly(Attempts.class, Attempts.NativeSql.class)
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains(Attempts.NativeSql.class.getName())
                    .contains("native SQL on site-scoped table qw_option")
                    .contains("@SiteScopedNativeSql"));

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
