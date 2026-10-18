package org.quarkpress.persistence.deployment.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.SQLInsert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.persistence.deployment.DbApp;
import org.quarkpress.persistence.deployment.Failures;
import org.quarkpress.persistence.runtime.SiteScopedEntity;

/** A class-level custom insert on a site-scoped entity is native SQL like any other, checked the same way. */
class ClassLevelSqlInsertTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.buildOnly(CustomInsert.class)
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains(CustomInsert.class.getName())
                    .contains("native SQL on site-scoped table qp_option in @SQLInsert"));

    @Entity
    @Table(name = "test_custom_insert")
    @Filter(name = SiteScopedEntity.FILTER)
    @SQLInsert(sql = "insert into qp_option (site_id, name) values (?, ?)")
    public static class CustomInsert extends SiteScopedEntity {
        @Id
        @GeneratedValue
        public Long id;
    }

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
