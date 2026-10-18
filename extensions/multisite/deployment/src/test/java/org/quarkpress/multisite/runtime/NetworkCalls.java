package org.quarkpress.multisite.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.Callable;
import org.quarkpress.persistence.NetworkScope;

/** Opens the network scope for tests, from the package the network scope trusts. */
@ApplicationScoped
public class NetworkCalls {

    @Inject
    NetworkScope network;

    public <T> T call(Callable<T> work) throws Exception {
        return network.call(work);
    }
}
