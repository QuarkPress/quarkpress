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

class JoinTableToOneTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.buildOnlyMapped(Owner.class, Target.class)
            .assertException(
                    failure -> assertThat(Failures.messages(failure))
                            .contains(
                                    Owner.class.getName()
                                            + ".target maps an association to a site-scoped entity through a join table, which carries no site; map it as a @ManyToOne field with join columns"));

    @Entity
    @Table(name = "jt_owner")
    @Filter(name = SiteScopedEntity.FILTER)
    public static class Owner extends SiteScopedEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;

        @ManyToOne
        @JoinTable(name = "jt_link")
        public Target target;
    }

    @Entity
    @Table(name = "jt_target")
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
