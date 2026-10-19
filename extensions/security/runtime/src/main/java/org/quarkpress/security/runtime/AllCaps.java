package org.quarkpress.security.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.quarkpress.core.RoleDefinition;

/**
 * A user's capabilities on one site, as WordPress builds them: start empty; copy each role's capabilities in the
 * user's role order (a later role replaces an earlier one's value); then set each stored role key to true and copy the
 * user's own capabilities over everything, so a user entry (false included) wins over every role. A key counts as a
 * role only when the site's table has it, whatever its stored value; a stored key the table lacks (a removed role, a
 * typo) is a plain capability.
 */
public final class AllCaps {

    private AllCaps() {}

    /**
     * The membership's roles on the site: keys of {@code roles} the site's table has, in order, then keys of
     * {@code capabilities} it has; each once.
     */
    public static List<String> roles(
            Map<String, RoleDefinition> siteRoles, List<String> roles, Map<String, Boolean> capabilities) {
        Set<String> found = new LinkedHashSet<>();
        for (String key : roles) {
            if (siteRoles.containsKey(key)) {
                found.add(key);
            }
        }
        for (String key : capabilities.keySet()) {
            if (siteRoles.containsKey(key)) {
                found.add(key);
            }
        }
        return new ArrayList<>(found);
    }

    public static Map<String, Boolean> of(
            Map<String, RoleDefinition> siteRoles, List<String> roles, Map<String, Boolean> capabilities) {
        Map<String, Boolean> all = new LinkedHashMap<>();
        for (String role : roles(siteRoles, roles, capabilities)) {
            all.putAll(siteRoles.get(role).capabilities());
        }
        for (String key : roles) {
            all.put(key, true);
        }
        all.putAll(capabilities);
        return all;
    }

    public static boolean holds(Map<String, Boolean> allcaps, String capability) {
        return Boolean.TRUE.equals(allcaps.get(capability));
    }
}
