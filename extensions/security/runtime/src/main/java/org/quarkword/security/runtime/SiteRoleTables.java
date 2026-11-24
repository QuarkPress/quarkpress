package org.quarkword.security.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.quarkword.core.RoleDefinition;
import org.quarkword.core.SiteId;
import org.quarkword.security.Roles;

/**
 * A site's role table: its {@code qw_user_roles} option when the site's roles were ever changed (a value that is not a
 * table counts as one without roles), else the default table of the running build. The reads are package-private so
 * that {@code SiteRoles} is the one public entry point; its reads are unguarded, like WordPress's {@code get_role}.
 */
@ApplicationScoped
public class SiteRoleTables {

    /** The site option that holds a site's whole role table once it changes. */
    public static final String OPTION = "qw_user_roles";

    @Inject
    Roles roles;

    @Inject
    CapabilityReads reads;

    List<RoleDefinition> forSite(SiteId site) {
        return reads.siteOption(site, OPTION).map(RoleTables::fromJson).orElse(roles.defaults());
    }

    Map<String, RoleDefinition> byKey(SiteId site) {
        return RoleTables.byKey(forSite(site));
    }
}
