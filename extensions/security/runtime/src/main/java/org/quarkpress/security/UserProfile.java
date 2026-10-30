package org.quarkpress.security;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

/**
 * A user of the network: one per OIDC subject. {@code nicename} is the URL-friendly name WordPress calls
 * {@code user_nicename}, unique in the network; {@code displayName} is shown as the author's name; {@code email} is the
 * address the identity provider last vouched for at a login, if any.
 */
public record UserProfile(
        UserId id,
        String subject,
        String displayName,
        String nicename,
        Optional<String> bio,
        Optional<SiteId> primarySite,
        Instant createdAt,
        Optional<Instant> lastLoginAt,
        Optional<String> email) {

    public UserProfile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(nicename, "nicename");
        Objects.requireNonNull(bio, "bio");
        Objects.requireNonNull(primarySite, "primarySite");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(lastLoginAt, "lastLoginAt");
        Objects.requireNonNull(email, "email");
    }

    /** A profile without e-mail. */
    public UserProfile(
            UserId id,
            String subject,
            String displayName,
            String nicename,
            Optional<String> bio,
            Optional<SiteId> primarySite,
            Instant createdAt,
            Optional<Instant> lastLoginAt) {
        this(id, subject, displayName, nicename, bio, primarySite, createdAt, lastLoginAt, Optional.empty());
    }
}
