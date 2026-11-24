package org.quarkword.persistence.deployment.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.persistence.Cacheable;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.persistence.deployment.DbApp;
import org.quarkword.persistence.deployment.Failures;
import org.quarkword.persistence.runtime.SiteScopedEntity;

class CacheableSiteEntityTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.buildOnly(Cached.class)
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains(Cached.class.getName())
                    .contains("must not be cached"));

    @Entity
    @Cacheable
    @Table(name = "test_cached")
    @Filter(name = SiteScopedEntity.FILTER)
    public static class Cached extends SiteScopedEntity {
        @Id
        @GeneratedValue
        public Long id;
    }

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
