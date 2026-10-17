package org.quarkpress.persistence.deployment.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.Filter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.persistence.deployment.DbApp;
import org.quarkpress.persistence.deployment.Failures;
import org.quarkpress.persistence.runtime.SiteScopedEntity;

class UnfilteredCollectionTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.buildOnly(Shelf.class, Book.class)
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("collection " + Shelf.class.getName() + "#books")
                    .contains("must carry @Filter(name = \"qp_site\")"));

    @Entity
    @Table(name = "test_shelf")
    @Filter(name = SiteScopedEntity.FILTER)
    public static class Shelf extends SiteScopedEntity {
        @Id
        @GeneratedValue
        public Long id;

        @OneToMany(mappedBy = "shelf")
        public List<Book> books = new ArrayList<>();
    }

    @Entity
    @Table(name = "test_book")
    @Filter(name = SiteScopedEntity.FILTER)
    public static class Book extends SiteScopedEntity {
        @Id
        @GeneratedValue
        public Long id;

        @ManyToOne
        public Shelf shelf;
    }

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
