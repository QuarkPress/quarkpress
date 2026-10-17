package org.quarkpress.elsewhere;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.core.runtime.SiteBinding;

/** Stands in for a QuarkPress module outside core and persistence that only compares with the network binding. */
@ApplicationScoped
public class NetworkReader {

    @Inject
    SiteBinding binding;

    public boolean network() {
        return binding.current().equals(Binding.NETWORK);
    }
}
