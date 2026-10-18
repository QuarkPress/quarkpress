package org.quarkpress.core;

/** The WordPress network capabilities that QuarkPress's network services require. */
public final class NetworkCapabilities {

    /** Create sites. */
    public static final String CREATE_SITES = "create_sites";

    /** Delete sites. */
    public static final String DELETE_SITES = "delete_sites";

    /** Change sites: addresses, names, flags and domains. */
    public static final String MANAGE_SITES = "manage_sites";

    private NetworkCapabilities() {}
}
