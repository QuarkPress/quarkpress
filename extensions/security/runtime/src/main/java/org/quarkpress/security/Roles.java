package org.quarkpress.security;

import java.util.List;
import java.util.Optional;
import org.quarkpress.core.RoleDefinition;

/**
 * The role table every site has until its roles are changed: WordPress's five roles (administrator, editor, author,
 * contributor, subscriber) with their capabilities, then the roles extensions register, in key order, with the
 * capabilities extensions grant appended. A site's current table is read through {@link SiteRoles}.
 */
public interface Roles {

    List<RoleDefinition> defaults();

    /** A role of the default table by exact key. */
    Optional<RoleDefinition> defaultRole(String key);
}
