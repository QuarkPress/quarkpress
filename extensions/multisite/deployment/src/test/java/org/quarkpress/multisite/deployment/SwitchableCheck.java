package org.quarkpress.multisite.deployment;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.quarkpress.core.CapabilityCheck;
import org.quarkpress.core.CapabilityDeniedException;

/** Replaces the open default check: denies the capabilities a test puts in {@link #DENIED}. */
@ApplicationScoped
public class SwitchableCheck implements CapabilityCheck {

    public static final Set<String> DENIED = ConcurrentHashMap.newKeySet();

    @Override
    public void requireNetwork(String capability) {
        if (DENIED.contains(capability)) {
            throw new CapabilityDeniedException(capability);
        }
    }
}
