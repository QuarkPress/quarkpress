package org.quarkpress.devui.runtime;

import jakarta.inject.Inject;
import java.util.concurrent.Callable;
import org.quarkpress.security.SystemIdentity;

/** The Dev UI identity of an application with the security module: the work runs as the system. */
public class SystemDevIdentity implements DevIdentity {

    @Inject
    SystemIdentity system;

    @Override
    public <T> T call(Callable<T> work) throws Exception {
        return system.call(work);
    }
}
