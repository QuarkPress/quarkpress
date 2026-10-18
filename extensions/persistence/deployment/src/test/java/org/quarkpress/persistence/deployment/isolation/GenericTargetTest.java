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

class GenericTargetTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.buildOnlyMapped(Owner.class, Target.class, Base.class)
            .assertException(
                    failure -> assertThat(Failures.messages(failure))
                            .contains(
                                    Base.class.getName()
                                            + ".target maps an association to a target that may be site-scoped but is not one concrete site-scoped entity class"));

    @MappedSuperclass
    public abstract static class Base<T extends SiteScopedEntity> extends SiteScopedEntity {
        @ManyToOne
        public T target;
    }

    @Entity
    @Table(name = "gt_owner")
    @Filter(name = SiteScopedEntity.FILTER)
    public static class Owner extends Base<Target> {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;
    }

    @Entity
    @Table(name = "gt_target")
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
