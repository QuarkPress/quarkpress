package org.quarkpress.persistence.deployment.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.persistence.*;
import java.util.HashSet;
import java.util.Set;
import org.hibernate.annotations.Filter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.persistence.deployment.DbApp;
import org.quarkpress.persistence.deployment.Failures;
import org.quarkpress.persistence.runtime.SiteScopedEntity;

class ManyToAnyTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.buildOnlyMapped(Owner.class, Target.class)
            .assertException(
                    failure -> assertThat(Failures.messages(failure))
                            .contains(
                                    Owner.class.getName()
                                            + ".targets maps an any-association (@Any or @ManyToAny) from a site-scoped entity; map it as a @ManyToOne field to a concrete site-scoped entity"));

    @Entity
    @Table(name = "ma_owner")
    @Filter(name = SiteScopedEntity.FILTER)
    public static class Owner extends SiteScopedEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;

        @org.hibernate.annotations.ManyToAny
        @org.hibernate.annotations.AnyDiscriminator(jakarta.persistence.DiscriminatorType.STRING)
        @org.hibernate.annotations.AnyDiscriminatorValue(discriminator = "T", entity = Target.class)
        @org.hibernate.annotations.AnyKeyJavaClass(Long.class)
        @jakarta.persistence.JoinTable(name = "ma_link")
        public Set<Object> targets = new HashSet<>();
    }

    @Entity
    @Table(name = "ma_target")
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
