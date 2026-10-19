package org.quarkpress.security.runtime;

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
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.runtime.QuarkPressJsonJdbcType;
import org.quarkpress.persistence.runtime.SiteScopedEntity;

/**
 * A user's membership of one site: the role keys (a JSON array, in order) and individual capabilities (a JSON object of
 * name to boolean; false is a denial).
 */
@Entity
@Table(name = "qp_site_membership")
@Filter(name = SiteScopedEntity.FILTER)
public class SiteMembershipEntity extends SiteScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "user_id", nullable = false)
    public long userId;

    @JdbcTypeCode(SqlTypes.JSON)
    @JdbcType(QuarkPressJsonJdbcType.class)
    @Column(name = "roles", nullable = false)
    public JsonNode roles;

    @JdbcTypeCode(SqlTypes.JSON)
    @JdbcType(QuarkPressJsonJdbcType.class)
    @Column(name = "capabilities", nullable = false)
    public JsonNode capabilities;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    public static SiteMembershipEntity create(
            SiteId site, UserId user, JsonNode roles, JsonNode capabilities, Instant now) {
        SiteMembershipEntity membership = new SiteMembershipEntity();
        membership.assignSite(site);
        membership.userId = user.value();
        membership.roles = roles;
        membership.capabilities = capabilities;
        membership.createdAt = now;
        return membership;
    }
}
