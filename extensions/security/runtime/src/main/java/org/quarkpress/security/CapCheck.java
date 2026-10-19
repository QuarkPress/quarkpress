package org.quarkpress.security;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

/**
 * The check a {@link UserCapabilitiesFilter} takes part in: the capability asked, the primitive capabilities it maps
 * to, the user (empty when logged out), the site and the object.
 */
public record CapCheck(
        String capability,
        Set<String> required,
        Optional<UserId> user,
        Optional<SiteId> site,
        Optional<CapObject> object) {

    public CapCheck {
        Objects.requireNonNull(capability, "capability");
        required = Set.copyOf(Objects.requireNonNull(required, "required"));
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(object, "object");
    }
}
