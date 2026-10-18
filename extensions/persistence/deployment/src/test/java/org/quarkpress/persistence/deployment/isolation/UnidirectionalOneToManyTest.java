package org.quarkpress.persistence.deployment.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;
import org.hibernate.annotations.Filter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.persistence.deployment.DbApp;
import org.quarkpress.persistence.deployment.Failures;
import org.quarkpress.persistence.runtime.SiteScopedEntity;

class UnidirectionalOneToManyTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.buildOnlyMapped(Basket.class, Item.class)
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains(Basket.class.getName() + ".items maps an association to a site-scoped entity as a"
                            + " unidirectional one-to-many; map it as a @ManyToOne field of the site-scoped entity"));

    /** Hibernate writes the items' foreign key from the basket's side, where the listener never sees the item. */
    @Entity
    @Table(name = "test_uni_basket")
    @Filter(name = SiteScopedEntity.FILTER)
    public static class Basket extends SiteScopedEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;

        @OneToMany
        @JoinColumn(name = "basket_id")
        @Filter(name = SiteScopedEntity.FILTER)
        public Set<Item> items = new HashSet<>();
    }

    @Entity
    @Table(name = "test_uni_item")
    @Filter(name = SiteScopedEntity.FILTER)
    public static class Item extends SiteScopedEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;
    }

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
