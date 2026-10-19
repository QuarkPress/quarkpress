package org.quarkpress.security;

import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

/**
 * The context a {@link MetaCapabilityFilter} receives: the capability being mapped, the checking user (empty when
 * logged out or for the system), the site and the object.
 */
public record MetaCapRequest(
        String capability, Optional<UserId> user, Optional<SiteId> site, Optional<CapObject> object) {

    public MetaCapRequest {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(object, "object");
    }
}
