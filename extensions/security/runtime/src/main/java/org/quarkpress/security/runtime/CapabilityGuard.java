package org.quarkpress.security.runtime;

import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    @Inject
    CapabilityEvaluator evaluator;

    @Inject
    InstallMode mode;

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

    /**
     * Requires that the current identity may give {@code given} (names to grant or deny, as a role or a user stores
     * them) on {@code site}, a site that changes answer for (the main site on a single site). The system and, in a
     * network, a network administrator may give anything. Anyone else may never give a network capability (granted or
     * denied), and may grant only names they hold on the site from their roles and individual entries; on a single
     * site, a user WordPress counts as super admin there (one who can delete users) may grant any other name. Denials
     * of other names are always allowed.
     */
    public void requireMayGive(SiteId site, Map<String, Boolean> given) {
        SecurityIdentity identity = JpaCapabilities.currentIdentity();
        Actor actor = Actor.of(identity);
        if (evaluator.givesAnything(actor)) {
            return;
        }
        boolean anyName = !mode.multisite()
                && actor instanceof Actor.User user
                && evaluator.isSuperAdmin(user.id(), Optional.of(site));
        Set<String> held = anyName ? Set.of() : evaluator.heldNames(actor, site);
        for (Map.Entry<String, Boolean> entry : given.entrySet()) {
            String name = entry.getKey();
            if (MetaCaps.NETWORK_ONLY.contains(name) || (entry.getValue() && !anyName && !held.contains(name))) {
                refuse(identity, "the caller may not give the capability " + name + " on site " + site.value());
            }
        }
    }

    private static void refuse(SecurityIdentity identity, String message) {
        if (identity == null || identity.isAnonymous()) {
            throw new UnauthorizedException(message);
        }
        throw new ForbiddenException(message);
    }
}
