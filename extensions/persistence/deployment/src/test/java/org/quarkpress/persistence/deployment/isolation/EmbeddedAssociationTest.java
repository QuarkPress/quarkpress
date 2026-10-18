package org.quarkpress.persistence.deployment.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.persistence.deployment.DbApp;
import org.quarkpress.persistence.deployment.Failures;
import org.quarkpress.persistence.runtime.SiteScopedEntity;

class EmbeddedAssociationTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.buildOnlyMapped(Shipment.class, Delivery.class, Region.class)
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains(Shipment.class.getName() + ".delivery maps an association to a site-scoped entity"
                            + " inside an embeddable; map it as a @ManyToOne field of the site-scoped entity"));

    @Embeddable
    public static class Delivery {
        @ManyToOne
        @JoinColumn(name = "region_id")
        public Region region;
    }

    @Entity
    @Table(name = "test_emb_shipment")
    @Filter(name = SiteScopedEntity.FILTER)
    public static class Shipment extends SiteScopedEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;

        @Embedded
        public Delivery delivery;
    }

    @Entity
    @Table(name = "test_emb_region")
    @Filter(name = SiteScopedEntity.FILTER)
    public static class Region extends SiteScopedEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;
    }

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
