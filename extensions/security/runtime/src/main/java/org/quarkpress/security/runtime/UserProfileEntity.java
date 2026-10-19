package org.quarkpress.security.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Optional;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.runtime.QuarkPressJsonJdbcType;
import org.quarkpress.security.UserProfile;

/** A user profile. Network data: no site filter applies. */
@Entity
@Table(name = "qp_user_profile")
public class UserProfileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "subject", nullable = false, length = 255)
    public String subject;

    @Column(name = "display_name", nullable = false, length = 250)
    public String displayName;

    @Column(name = "nicename", nullable = false, length = 50)
    public String nicename;

    @Column(name = "bio")
    public String bio;

    @Column(name = "primary_site_id")
    public Long primarySiteId;

    @JdbcTypeCode(SqlTypes.JSON)
    @JdbcType(QuarkPressJsonJdbcType.class)
    @Column(name = "meta", nullable = false)
    public JsonNode meta;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "last_login_at")
    public Instant lastLoginAt;

    public UserProfile toProfile() {
        return new UserProfile(
                UserId.of(id),
                subject,
                displayName,
                nicename,
                Optional.ofNullable(bio),
                Optional.ofNullable(primarySiteId).map(SiteId::of),
                createdAt,
                Optional.ofNullable(lastLoginAt));
    }
}
