package org.quarkword.persistence.deployment.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.persistence.deployment.DbApp;
import org.quarkword.persistence.deployment.Failures;

class CachingReenabledTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.buildOnly()
            .overrideConfigKey("quarkus.hibernate-orm.\"quarkword\".second-level-caching-enabled", "true")
            .assertException(
                    failure -> assertThat(Failures.messages(failure)).contains("second-level-caching-enabled"));

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
