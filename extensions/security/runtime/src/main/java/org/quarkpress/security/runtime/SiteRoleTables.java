package org.quarkpress.security.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.quarkpress.core.RoleDefinition;
import org.quarkpress.core.SiteId;
import org.quarkpress.security.Roles;

/**
 * A site's role table: its {@code qp_user_roles} option when the site's roles were ever changed (a value that is not a
 * table counts as one without roles), else the default table of the running build. The reads are package-private so
 * that {@code SiteRoles} is the one public entry point; its reads are unguarded, like WordPress's {@code get_role}.
 */
@ApplicationScoped
public class SiteRoleTables {

    /** The site option that holds a site's whole role table once it changes. */
    public static final String OPTION = "qp_user_roles";

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
