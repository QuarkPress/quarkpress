package org.quarkpress.security.runtime;

import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.quarkpress.core.SiteId;
import org.quarkpress.security.CapObject;
import org.quarkpress.security.Capabilities;

/**
 * Checks a capability of the current identity in service code, with the exceptions {@code @PermissionsAllowed}
 * throws: {@link UnauthorizedException} when nobody is authenticated, {@link ForbiddenException} otherwise (also for an
 * authenticated caller that has no QuarkPress profile, which holds no capability but must not be challenged to log in).
 */
@ApplicationScoped
public class CapabilityGuard {

    @Inject
    Capabilities capabilities;

    /** Requires {@code capability} on {@code site}. */
    public void require(SiteId site, String capability, Optional<CapObject> object) {
        SecurityIdentity identity = JpaCapabilities.currentIdentity();
        if (!capabilities.canForSite(identity, site, capability, object)) {
            refuse(identity, "the caller lacks the capability " + capability + " on site " + site.value());
        }
    }

    /** Requires {@code capability} for the binding in effect. */
    public void requireHere(String capability) {
        SecurityIdentity identity = JpaCapabilities.currentIdentity();
        if (!capabilities.can(identity, capability, Optional.empty())) {
            refuse(identity, "the caller lacks the capability " + capability + " for this request");
        }
    }

    private static void refuse(SecurityIdentity identity, String message) {
        if (identity == null || identity.isAnonymous()) {
            throw new UnauthorizedException(message);
        }
        throw new ForbiddenException(message);
    }
}
