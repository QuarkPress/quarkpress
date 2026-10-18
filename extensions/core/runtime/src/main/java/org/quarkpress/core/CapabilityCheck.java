package org.quarkpress.core;

/**
 * Decides whether the current caller holds a network capability. QuarkPress's network services ask it before every
 * write. The security module provides the real check; without it the default check allows every caller, so an
 * application without the security module must not expose network services to untrusted callers.
 */
public interface CapabilityCheck {

    /** Returns when the caller holds {@code capability}; throws {@link CapabilityDeniedException} otherwise. */
    void requireNetwork(String capability);
}
