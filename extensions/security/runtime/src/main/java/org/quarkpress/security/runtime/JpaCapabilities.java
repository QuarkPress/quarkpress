package org.quarkpress.security.runtime;

import io.quarkus.arc.Arc;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.security.CapObject;
import org.quarkpress.security.Capabilities;

/** Capability checks through the evaluator, with the current identity and binding where none is given. */
@ApplicationScoped
public class JpaCapabilities implements Capabilities {

    @Inject
    CapabilityEvaluator evaluator;

    @Inject
    CheckSite checkSite;

    @Inject
    InstallMode mode;

    @Inject
    CapabilityReads reads;

    @Inject
    SiteRoleTables tables;

    /** The current request's identity; null (anonymous) when no request context is active. */
    public static SecurityIdentity currentIdentity() {
        if (!Arc.container().requestContext().isActive()) {
            return null;
        }
        return Arc.container().instance(CurrentIdentityAssociation.class).get().getIdentity();
    }

    @Override
    public boolean can(String capability) {
        return can(currentIdentity(), capability, Optional.empty());
    }

    @Override
    public boolean can(String capability, CapObject object) {
        return can(currentIdentity(), capability, Optional.of(Objects.requireNonNull(object, "object")));
    }

    @Override
    public boolean can(SecurityIdentity identity, String capability, Optional<CapObject> object) {
        return evaluator.check(Actor.of(identity), checkSite.current(), capability, object);
    }

    @Override
    public boolean canForSite(SecurityIdentity identity, SiteId site, String capability, Optional<CapObject> object) {
        return evaluator.check(Actor.of(identity), target(site), capability, object);
    }

    @Override
    public boolean userCan(UserId user, SiteId site, String capability, Optional<CapObject> object) {
        return evaluator.check(new Actor.User(user), target(site), capability, object);
    }

    @Override
    public boolean anonymousCan(SiteId site, String capability, Optional<CapObject> object) {
        return evaluator.check(Actor.ANONYMOUS, target(site), capability, object);
    }

    /**
     * The site an explicit-site check answers for. On a single site WordPress ignores the site id (any positive id
     * answers for the only site), so the main site; in a network, the site given. Ids of 0 or less, which WordPress
     * answers false for, cannot be expressed: {@code SiteId} is positive.
     */
    private Optional<SiteId> target(SiteId site) {
        Objects.requireNonNull(site, "site");
        return Optional.of(mode.multisite() ? site : checkSite.main());
    }

    @Override
    public Map<String, Boolean> allCapabilities(UserId user, SiteId site) {
        Objects.requireNonNull(user, "user");
        SiteId target = target(site).orElseThrow();
        return reads.membership(target, user)
                .map(membership -> Collections.unmodifiableMap(
                        AllCaps.of(tables.byKey(target), membership.roles(), membership.capabilities())))
                .orElseGet(Map::of);
    }

    @Override
    public boolean isSuperAdmin(UserId user) {
        return evaluator.isSuperAdmin(user, Optional.of(checkSite.main()));
    }
}
