package org.quarkpress.security.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.arc.All;
import io.quarkus.arc.InstanceHandle;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.quarkpress.core.Filters;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.security.CapCheck;
import org.quarkpress.security.CapObject;
import org.quarkpress.security.UserCapabilitiesFilter;

/**
 * WordPress's check: map the capability; in a network a network administrator passes unless the result holds
 * {@code do_not_allow}; the system passes unless it holds {@code do_not_allow} or {@code unfiltered_html}; anyone else
 * needs every required name in their capabilities on the site, after the core grants and the
 * {@link UserCapabilitiesFilter}s, with {@code exist} always held and {@code do_not_allow} never. Nothing is cached.
 */
@ApplicationScoped
public class CapabilityEvaluator {

    @Inject
    InstallMode mode;

    @Inject
    CapabilityReads reads;

    @Inject
    SiteRoleTables tables;

    @Inject
    MetaCaps metaCaps;

    @Inject
    SecurityConfig config;

    @Inject
    @All
    List<InstanceHandle<UserCapabilitiesFilter>> filterHandles;

    private List<UserCapabilitiesFilter> filters;

    @PostConstruct
    void orderFilters() {
        filters = Filters.ordered(filterHandles);
    }

    /**
     * The answer for {@code actor}. A user id without a profile is a logged-out visitor, as WordPress uses the
     * anonymous user for an id that does not exist.
     */
    public boolean check(Actor actor, Optional<SiteId> site, String capability, Optional<CapObject> object) {
        return decide(known(actor), site, capability, object);
    }

    private Actor known(Actor actor) {
        return actor instanceof Actor.User user && !reads.profileExists(user.id()) ? Actor.ANONYMOUS : actor;
    }

    /** The check for an actor already known to exist (nested checks of the mapping come here). */
    private boolean decide(Actor actor, Optional<SiteId> site, String capability, Optional<CapObject> object) {
        Set<String> required = metaCaps.map(capability, object, new Context(actor, site));
        if (actor instanceof Actor.SystemActor) {
            return !required.contains(MetaCaps.DO_NOT_ALLOW) && !required.contains("unfiltered_html");
        }
        if (actor instanceof Actor.User user && mode.multisite() && reads.networkAdmin(user.id())) {
            return !required.contains(MetaCaps.DO_NOT_ALLOW);
        }
        Map<String, Boolean> caps = CoreGrants.apply(allCaps(actor, site), mode.multisite());
        CapCheck check = new CapCheck(capability, required, userOf(actor), site, object);
        caps = Filters.apply(filters, caps, (filter, current) -> filter.apply(current, check));
        Map<String, Boolean> held = new HashMap<>(caps);
        held.put(MetaCaps.EXIST, true);
        held.remove(MetaCaps.DO_NOT_ALLOW);
        for (String name : required) {
            if (!Boolean.TRUE.equals(held.get(name))) {
                return false;
            }
        }
        return true;
    }

    /** The primitive capabilities {@code capability} maps to for this actor and site. */
    public Set<String> required(Actor actor, Optional<SiteId> site, String capability, Optional<CapObject> object) {
        return metaCaps.map(capability, object, new Context(known(actor), site));
    }

    /** In a network, a network administrator; on a single site, a user who can delete users on {@code site}. */
    public boolean isSuperAdmin(UserId user, Optional<SiteId> site) {
        if (mode.multisite()) {
            return reads.networkAdmin(user);
        }
        return site.isPresent() && check(new Actor.User(user), site, "delete_users", Optional.empty());
    }

    /** {@link #isSuperAdmin(UserId, Optional)} for a user already known to exist. */
    private boolean isKnownSuperAdmin(UserId user, Optional<SiteId> site) {
        if (mode.multisite()) {
            return reads.networkAdmin(user);
        }
        return site.isPresent() && decide(new Actor.User(user), site, "delete_users", Optional.empty());
    }

    /** The actor's capabilities on the site before grants and filters; empty without a user or a site. */
    private Map<String, Boolean> allCaps(Actor actor, Optional<SiteId> site) {
        if (!(actor instanceof Actor.User user) || site.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return reads.membership(site.get(), user.id())
                .map(membership -> AllCaps.of(tables.byKey(site.get()), membership.roles(), membership.capabilities()))
                .orElseGet(LinkedHashMap::new);
    }

    private static Optional<UserId> userOf(Actor actor) {
        return actor instanceof Actor.User user ? Optional.of(user.id()) : Optional.empty();
    }

    /** What the mapping may ask, answered for one actor and site. */
    private final class Context implements MapContext {

        private final Actor actor;
        private final Optional<SiteId> site;

        Context(Actor actor, Optional<SiteId> site) {
            this.actor = actor;
            this.site = site;
        }

        @Override
        public boolean multisite() {
            return mode.multisite();
        }

        @Override
        public Optional<UserId> user() {
            return userOf(actor);
        }

        @Override
        public Optional<SiteId> site() {
            return site;
        }

        @Override
        public boolean isSuperAdmin() {
            if (actor instanceof Actor.SystemActor) {
                return true;
            }
            return actor instanceof Actor.User user && isKnownSuperAdmin(user.id(), site);
        }

        @Override
        public boolean isSuperAdmin(UserId other) {
            return mode.multisite() && reads.networkAdmin(other);
        }

        @Override
        public boolean userCan(String capability) {
            return decide(actor, site, capability, Optional.empty());
        }

        @Override
        public Optional<JsonNode> siteOption(String name) {
            return site.flatMap(id -> reads.siteOption(id, name));
        }

        @Override
        public Optional<JsonNode> networkOption(String name) {
            return reads.networkOption(name);
        }

        @Override
        public boolean disallowUnfilteredHtml() {
            return config.disallowUnfilteredHtml();
        }
    }
}
