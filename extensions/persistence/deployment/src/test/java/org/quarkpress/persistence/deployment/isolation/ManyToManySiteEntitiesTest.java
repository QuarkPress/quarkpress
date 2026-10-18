package org.quarkpress.persistence.deployment.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;
import org.hibernate.annotations.Filter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.persistence.deployment.DbApp;
import org.quarkpress.persistence.deployment.Failures;
import org.quarkpress.persistence.runtime.SiteScopedEntity;

class ManyToManySiteEntitiesTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.buildOnlyMapped(Post.class, Label.class)
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains(Post.class.getName() + ".labels maps a many-to-many association between site-scoped"
                            + " entities; map the join table as a site-scoped entity with composite keys instead"));

    @Entity
    @Table(name = "test_m2m_post")
    @Filter(name = SiteScopedEntity.FILTER)
    public static class Post extends SiteScopedEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;

        @ManyToMany
        @Filter(name = SiteScopedEntity.FILTER)
        public Set<Label> labels = new HashSet<>();
    }

    @Entity
    @Table(name = "test_m2m_label")
    @Filter(name = SiteScopedEntity.FILTER)
    public static class Label extends SiteScopedEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;
    }

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
