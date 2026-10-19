package org.quarkpress.security;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.core.RoleDefinition;
import org.quarkpress.core.SiteId;

/**
 * A site's roles, as WordPress's role API manages them. Until a site's roles change it has the default table
 * ({@link Roles}); the first change stores the whole table for that site, so later default changes of QuarkPress
 * releases no longer affect it. Reads are unguarded, like WordPress's {@code get_role}; changes require
 * {@code manage_options} on the site and are visible to the next check. A change that changes nothing stores nothing.
 * On a single site every id means the main site.
 */
public interface SiteRoles {

    List<RoleDefinition> roles(SiteId site);

    Optional<RoleDefinition> role(SiteId site, String key);

    /** Adds a role at the end; empty, changing nothing, when the key is empty or the site has the role. */
    Optional<RoleDefinition> add(SiteId site, String key, String displayName, Map<String, Boolean> capabilities);

    /**
     * Removes a role; false when the site has none with that key. A {@code default_role} option naming it becomes
     * {@code subscriber}. Users keep the key, which then counts as a plain capability.
     */
    boolean remove(SiteId site, String key);

    /** Sets a capability of a role (false is a denial); false when the site has no such role. */
    boolean setCapability(SiteId site, String role, String capability, boolean grant);

    /** Removes a capability from a role; false when the site has no such role or the role lacks it. */
    boolean removeCapability(SiteId site, String role, String capability);
}
