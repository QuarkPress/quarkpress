package org.quarkpress.multisite.runtime;

/** Where a request's host lies relative to the network domain. */
public enum HostClass {
    /** The network domain itself, with or without {@code www.}. */
    NETWORK,
    /** A subdomain of the network domain. */
    SUBDOMAIN,
    /** Any other host. */
    FOREIGN
}
