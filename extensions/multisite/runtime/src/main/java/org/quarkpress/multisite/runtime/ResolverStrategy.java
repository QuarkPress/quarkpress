package org.quarkpress.multisite.runtime;

/** A built-in site resolution strategy. */
public enum ResolverStrategy {
    /** Domain aliases, and sites on domains outside the network domain. */
    DOMAIN,
    /** Sites on subdomains of the network domain. */
    SUBDOMAIN,
    /** Sites under a path of the network domain. */
    PATH
}
