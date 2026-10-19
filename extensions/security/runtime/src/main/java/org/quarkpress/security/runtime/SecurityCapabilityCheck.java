package org.quarkpress.security.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.quarkpress.core.CapabilityCheck;
import org.quarkpress.core.CapabilityDeniedException;
import org.quarkpress.security.Capabilities;

/**
 * The network services' check with the security module: the current identity must hold the capability for the binding
 * in effect (in a network, network administrators and individual grants; on a single site nobody, since no role holds
 * a network capability). Replaces core's check that allows everyone.
 */
@ApplicationScoped
public class SecurityCapabilityCheck implements CapabilityCheck {

    @Inject
    Capabilities capabilities;

    @Override
    public void requireNetwork(String capability) {
        if (!capabilities.can(capability)) {
            throw new CapabilityDeniedException(capability);
        }
    }
}
