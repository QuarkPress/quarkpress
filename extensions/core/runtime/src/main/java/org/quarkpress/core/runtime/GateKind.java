package org.quarkpress.core.runtime;

/** How a gated method behaves when its feature is disabled. Decided at build time. */
public enum GateKind {
    /** Event observer: do nothing. */
    OBSERVER,
    /** Filter method: return the first argument. */
    FILTER,
    /** Anything else: throw SiteFeatureDisabledException. */
    OTHER
}
