package org.quarkpress.media.storage.s3.deployment;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import org.quarkpress.security.runtime.InstallMode;

/**
 * Puts the security module in multisite mode without the multisite module. Same as the media deployment test helper of
 * that name.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class TestMultisiteMode implements InstallMode {

    @Override
    public boolean multisite() {
        return true;
    }
}
