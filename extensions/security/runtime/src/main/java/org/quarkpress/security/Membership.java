package org.quarkpress.security;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

/**
 * A user's membership of a site as stored: role keys in order and individual capabilities (false is a denial). A key
 * of {@code capabilities} that the site's table has is a role too.
 */
public record Membership(
        SiteId site, UserId user, List<String> roles, Map<String, Boolean> capabilities, Instant createdAt) {

    public Membership {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(user, "user");
        roles = List.copyOf(roles);
        capabilities = Collections.unmodifiableMap(new LinkedHashMap<>(capabilities));
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
