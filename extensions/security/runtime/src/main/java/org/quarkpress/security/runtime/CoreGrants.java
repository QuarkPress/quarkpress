package org.quarkpress.security.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The grants WordPress adds to every check before other filters: {@code install_languages} for users who can update
 * core or install plugins or themes, {@code resume_plugins} for those who can activate plugins, {@code resume_themes}
 * for those who can switch themes, and {@code view_site_health_checks} for those who can install plugins, on a single
 * site only (in a network only its administrators, who need no grant, see site health).
 */
public final class CoreGrants {

    private CoreGrants() {}

    public static Map<String, Boolean> apply(Map<String, Boolean> allcaps, boolean multisite) {
        Map<String, Boolean> granted = new LinkedHashMap<>(allcaps);
        if (AllCaps.holds(allcaps, "update_core")
                || AllCaps.holds(allcaps, "install_plugins")
                || AllCaps.holds(allcaps, "install_themes")) {
            granted.put("install_languages", true);
        }
        if (AllCaps.holds(allcaps, "activate_plugins")) {
            granted.put("resume_plugins", true);
        }
        if (AllCaps.holds(allcaps, "switch_themes")) {
            granted.put("resume_themes", true);
        }
        if (AllCaps.holds(allcaps, "install_plugins") && !multisite) {
            granted.put("view_site_health_checks", true);
        }
        return granted;
    }
}
