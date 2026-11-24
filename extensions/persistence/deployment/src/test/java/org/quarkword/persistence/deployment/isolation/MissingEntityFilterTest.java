package org.quarkword.persistence.deployment.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.persistence.deployment.DbApp;
import org.quarkword.persistence.deployment.Failures;
import org.quarkword.persistence.runtime.SiteScopedEntity;

class MissingEntityFilterTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.buildOnly(Unfiltered.class)
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains(Unfiltered.class.getName())
                    .contains("must carry @Filter(name = \"qw_site\")"));

    @Entity
    @Table(name = "test_unfiltered")
    public static class Unfiltered extends SiteScopedEntity {
        @Id
        @GeneratedValue
        public Long id;
    }

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
