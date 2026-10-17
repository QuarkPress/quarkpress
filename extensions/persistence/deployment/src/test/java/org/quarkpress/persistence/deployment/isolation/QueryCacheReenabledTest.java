package org.quarkpress.persistence.deployment.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.persistence.deployment.DbApp;
import org.quarkpress.persistence.deployment.Failures;

class QueryCacheReenabledTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.buildOnly()
            .overrideConfigKey(
                    "quarkus.hibernate-orm.\"quarkpress\".unsupported-properties.\"hibernate.cache.use_query_cache\"",
                    "true")
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("hibernate.cache.use_query_cache")
                    .contains("must not be true"));

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
