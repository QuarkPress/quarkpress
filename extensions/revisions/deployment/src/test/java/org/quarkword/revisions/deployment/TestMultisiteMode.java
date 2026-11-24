package org.quarkword.revisions.deployment;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import org.quarkword.security.runtime.InstallMode;

/** Puts the security module in multisite mode without the multisite module, for tests of multisite rules. */
@Alternative
@Priority(1)
@ApplicationScoped
public class TestMultisiteMode implements InstallMode {

    @Override
    public boolean multisite() {
        return true;
    }
}
