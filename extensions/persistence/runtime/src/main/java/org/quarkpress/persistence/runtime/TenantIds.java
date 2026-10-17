package org.quarkpress.persistence.runtime;

import org.quarkpress.core.SiteId;
import org.quarkpress.core.runtime.Binding;

/**
 * The values that stand for the current site in the site filter: a site id, {@value #NETWORK} for the whole network
 * (sees every site) and {@value #UNRESOLVED} for no site (no site has id 0, so it sees nothing).
 */
public final class TenantIds {

    public static final String NETWORK = "-1";
    public static final String UNRESOLVED = "0";

    private TenantIds() {}

    public static String of(Binding binding) {
        return switch (binding.kind()) {
            case SITE -> of(binding.site());
            case NETWORK -> NETWORK;
            case UNRESOLVED -> UNRESOLVED;
        };
    }

    public static String of(SiteId site) {
        return Long.toString(site.value());
    }

    /** The value to compare with the {@code siteId} attribute in a query. */
    public static Object param(SiteId site) {
        return Long.valueOf(site.value());
    }
}
