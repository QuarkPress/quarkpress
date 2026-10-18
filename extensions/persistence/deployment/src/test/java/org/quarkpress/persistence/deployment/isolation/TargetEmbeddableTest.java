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

class TargetEmbeddableTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.buildOnlyMapped(Owner.class, Target.class, Part.class, PartImpl.class)
            .assertException(
                    failure -> assertThat(Failures.messages(failure))
                            .contains(
                                    Owner.class.getName()
                                            + ".part maps an embeddable through @TargetEmbeddable or as an interface or abstract type, whose associations cannot be checked; use a concrete @Embeddable class"));

    @Embeddable
    public interface Part {}

    @Embeddable
    public static class PartImpl implements Part {
        @ManyToOne
        @JoinColumn(name = "target_id")
        public Target target;
    }

    @Entity
    @Table(name = "te_owner")
    @Filter(name = SiteScopedEntity.FILTER)
    public static class Owner extends SiteScopedEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;

        @Embedded
        @org.hibernate.annotations.TargetEmbeddable(PartImpl.class)
        public Part part;
    }

    @Entity
    @Table(name = "te_target")
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
