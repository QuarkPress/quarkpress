package org.quarkword.security.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.Callable;
import org.quarkword.persistence.NetworkScope;

/** Stands in for security module code that opens the network scope, in tests. */
@ApplicationScoped
public class NetworkProbe {

    @Inject
    NetworkScope network;

    public <T> T inNetwork(Callable<T> work) throws Exception {
        return network.call(work);
    }
}
