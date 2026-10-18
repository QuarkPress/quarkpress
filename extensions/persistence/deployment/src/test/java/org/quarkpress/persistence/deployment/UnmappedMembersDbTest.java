package org.quarkpress.persistence.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.persistence.*;
import java.util.Map;
import org.hibernate.annotations.Filter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.persistence.runtime.SiteScopedEntity;

/** Helper members and basic map keys on a site-scoped entity are not associations and must not fail the build. */
class UnmappedMembersDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.with(Plain.class);

    @Entity
    @Table(name = "test_plain")
    @Filter(name = SiteScopedEntity.FILTER)
    public static class Plain extends SiteScopedEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;

        @Transient
        public Map<Object, Object> scratch;

        @ElementCollection
        @CollectionTable(name = "test_plain_labels")
        public Map<String, String> labels;

        public Map<Object, Object> asProperties() {
            return Map.of();
        }
    }

    @Test
    void theApplicationBuilds() {
        assertThat(Plain.class).isNotNull();
    }
}
