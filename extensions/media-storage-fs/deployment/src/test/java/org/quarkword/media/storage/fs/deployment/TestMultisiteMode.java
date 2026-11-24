package org.quarkword.media.storage.fs.deployment;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import org.quarkword.security.runtime.InstallMode;

/** Puts the security module in multisite mode without the multisite module. Same as the media test helper. */
@Alternative
@Priority(1)
@ApplicationScoped
public class TestMultisiteMode implements InstallMode {

    @Override
    public boolean multisite() {
        return true;
    }
}
