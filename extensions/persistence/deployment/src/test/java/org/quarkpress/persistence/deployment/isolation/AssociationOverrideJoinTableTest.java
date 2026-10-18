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

class AssociationOverrideJoinTableTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.buildOnlyMapped(Owner.class, Target.class, Parent.class)
            .assertException(
                    failure -> assertThat(Failures.messages(failure))
                            .contains(
                                    Owner.class.getName()
                                            + " overrides an association with a join table (@AssociationOverride); a join table carries no site, so keep the join columns"));

    @MappedSuperclass
    public abstract static class Parent extends SiteScopedEntity {
        @ManyToOne
        @JoinColumn(name = "target_id")
        public Target target;
    }

    @Entity
    @AssociationOverride(name = "target", joinTable = @JoinTable(name = "ao_link"))
    @Table(name = "ao_owner")
    @Filter(name = SiteScopedEntity.FILTER)
    public static class Owner extends Parent {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;
    }

    @Entity
    @Table(name = "ao_target")
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
