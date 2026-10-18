package org.quarkpress.persistence.deployment.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.persistence.*;
import org.hibernate.annotations.Filter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.persistence.deployment.DbApp;
import org.quarkpress.persistence.deployment.Failures;
import org.quarkpress.persistence.runtime.SiteScopedEntity;

class ArrayManyToManyTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.buildOnlyMapped(Owner.class, Target.class)
            .assertException(
                    failure -> assertThat(Failures.messages(failure))
                            .contains(
                                    Owner.class.getName()
                                            + ".targets maps a many-to-many association between site-scoped entities; map the join table as a site-scoped entity with composite keys instead"));

    @Entity
    @Table(name = "am_owner")
    @Filter(name = SiteScopedEntity.FILTER)
    public static class Owner extends SiteScopedEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;

        @ManyToMany
        public Target[] targets;
    }

    @Entity
    @Table(name = "am_target")
    @Filter(name = SiteScopedEntity.FILTER)
    public static class Target extends SiteScopedEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;
    }

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
