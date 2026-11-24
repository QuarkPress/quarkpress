package org.quarkword.core.runtime;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import org.quarkword.core.CapabilityCheck;

/** Allows every caller; replaced by the security module's check. */
@DefaultBean
@ApplicationScoped
public class OpenCapabilityCheck implements CapabilityCheck {

    @Override
    public void requireNetwork(String capability) {
        // Without the security module there is no caller identity to check.
    }
}
