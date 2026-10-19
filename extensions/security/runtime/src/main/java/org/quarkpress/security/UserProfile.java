package org.quarkpress.security;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

/**
 * A user of the network: one per OIDC subject. {@code nicename} is the URL-friendly name WordPress calls
 * {@code user_nicename}, unique in the network; {@code displayName} is shown as the author's name.
 */
public record UserProfile(
        UserId id,
        String subject,
        String displayName,
        String nicename,
        Optional<String> bio,
        Optional<SiteId> primarySite,
        Instant createdAt,
        Optional<Instant> lastLoginAt) {

    public UserProfile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(nicename, "nicename");
        Objects.requireNonNull(bio, "bio");
        Objects.requireNonNull(primarySite, "primarySite");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(lastLoginAt, "lastLoginAt");
    }
}
