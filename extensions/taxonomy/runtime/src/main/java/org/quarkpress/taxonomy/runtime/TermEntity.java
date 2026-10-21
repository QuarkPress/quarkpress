package org.quarkpress.taxonomy.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.Optional;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OptimisticLock;
import org.hibernate.type.SqlTypes;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TermId;
import org.quarkpress.persistence.runtime.QuarkPressJsonJdbcType;
import org.quarkpress.persistence.runtime.SiteScopedEntity;
import org.quarkpress.taxonomy.TermSnapshot;

/**
 * A term of one taxonomy (WordPress's term and term-taxonomy rows together: the id serves as both). The count is
 * outside the optimistic version, since assignments and post status changes by others change it.
 */
@Entity
@Table(name = "qp_term")
@Filter(name = SiteScopedEntity.FILTER)
public class TermEntity extends SiteScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "taxonomy", nullable = false, length = 32)
    public String taxonomy;

    @Column(name = "name", nullable = false, length = 200)
    public String name;

    @Column(name = "slug", nullable = false, length = 200)
    public String slug;

    @Column(name = "parent_id")
    public Long parentId;

    @Column(name = "description", nullable = false)
    public String description;

    @OptimisticLock(excluded = true)
    @Column(name = "count", nullable = false)
    public int termCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @JdbcType(QuarkPressJsonJdbcType.class)
    @Column(name = "meta", nullable = false)
    public JsonNode meta;

    @Version
    @Column(name = "version", nullable = false)
    public int version;

    /** A new row of {@code site} in {@code taxonomy} with empty texts, no parent and empty meta. */
    public static TermEntity create(SiteId site, String taxonomy) {
        TermEntity term = new TermEntity();
        term.assignSite(site);
        term.taxonomy = taxonomy;
        term.name = "";
        term.slug = "";
        term.description = "";
        term.meta = JsonNodeFactory.instance.objectNode();
        return term;
    }

    public TermSnapshot toSnapshot() {
        return new TermSnapshot(
                TermId.of(id),
                site(),
                taxonomy,
                name,
                slug,
                description,
                Optional.ofNullable(parentId).map(TermId::of),
                termCount,
                version);
    }
}
