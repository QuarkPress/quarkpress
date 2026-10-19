package org.quarkpress.security;

import java.util.Set;
import org.quarkpress.core.FilterSpi;

/**
 * Changes the primitive capabilities a capability maps to (WordPress's {@code map_meta_cap} filter). It runs after
 * every mapping step, also for the steps a mapping takes internally (reading a draft maps editing it first), and for
 * network administrators too: adding {@code do_not_allow} is the one way to refuse them something. Beans are applied
 * in {@code @Priority} order, higher first (WordPress runs lower numbers first); equal priorities by class name.
 */
@FilterSpi
public interface MetaCapabilityFilter {

    Set<String> apply(Set<String> requiredCaps, MetaCapRequest req);
}
