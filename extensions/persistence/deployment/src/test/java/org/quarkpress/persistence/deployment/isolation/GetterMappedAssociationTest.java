package org.quarkpress.persistence.deployment.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.Filter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.persistence.deployment.DbApp;
import org.quarkpress.persistence.deployment.Failures;
import org.quarkpress.persistence.runtime.SiteScopedEntity;

class GetterMappedAssociationTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.buildOnlyMapped(Note.class, Folder.class)
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains(Note.class.getName() + ".getFolder() maps an association to a site-scoped entity on a"
                            + " getter; map associations between site-scoped entities on fields so the listener can"
                            + " check their site"));

    /**
     * Field access (the id is on a field), so Hibernate ignores the getter's mapping; QuarkPress still refuses it,
     * because a getter-mapped association would escape the listener's field check.
     */
    @Entity
    @Table(name = "test_getter_note")
    @Filter(name = SiteScopedEntity.FILTER)
    public static class Note extends SiteScopedEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;

        @Transient
        private Folder folder;

        @ManyToOne
        public Folder getFolder() {
            return folder;
        }

        public void setFolder(Folder folder) {
            this.folder = folder;
        }
    }

    @Entity
    @Table(name = "test_getter_folder")
    @Filter(name = SiteScopedEntity.FILTER)
    public static class Folder extends SiteScopedEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;
    }

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
