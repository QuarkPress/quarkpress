package org.quarkword.persistence.deployment.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.NamedNativeQuery;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.persistence.deployment.DbApp;
import org.quarkword.persistence.deployment.Failures;
import org.quarkword.persistence.runtime.SiteScopedEntity;

class NamedNativeQueryTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.buildOnly(Named.class)
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains(Named.class.getName())
                    .contains("native SQL on site-scoped table qw_option in @NamedNativeQuery"));

    @Entity
    @Table(name = "test_named")
    @Filter(name = SiteScopedEntity.FILTER)
    @NamedNativeQuery(name = "everySite", query = "select * from qw_option")
    public static class Named extends SiteScopedEntity {
        @Id
        @GeneratedValue
        public Long id;
    }

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
