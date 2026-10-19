package org.quarkpress.security;

import java.util.Map;
import org.quarkpress.core.FilterSpi;

/**
 * Changes the capabilities a user holds for one check (WordPress's {@code user_has_cap} filter). It sees a copy of the
 * user's capabilities on the site and returns the ones to use; afterwards {@code exist} is always held and
 * {@code do_not_allow} never. Not consulted for network administrators or the system. Beans are applied in
 * {@code @Priority} order, higher first (WordPress runs lower numbers first); equal priorities by class name.
 */
@FilterSpi
public interface UserCapabilitiesFilter {

    Map<String, Boolean> apply(Map<String, Boolean> caps, CapCheck check);
}
