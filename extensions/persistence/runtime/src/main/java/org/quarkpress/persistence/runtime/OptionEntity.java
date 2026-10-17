package org.quarkpress.persistence.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.quarkpress.core.SiteId;

@Entity
@Table(name = "qp_option")
@Filter(name = SiteScopedEntity.FILTER)
public class OptionEntity extends SiteScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "name", nullable = false, length = 191)
    public String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @JdbcType(QuarkPressJsonJdbcType.class)
    @Column(name = "value", nullable = false)
    public JsonNode value;

    @Column(name = "autoload", nullable = false)
    public boolean autoload;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    public static OptionEntity create(SiteId site, String name, JsonNode value, boolean autoload, Instant now) {
        OptionEntity option = new OptionEntity();
        option.assignSite(site);
        option.name = StoredOptionNames.require(name);
        option.value = value;
        option.autoload = autoload;
        option.updatedAt = now;
        return option;
    }
}
