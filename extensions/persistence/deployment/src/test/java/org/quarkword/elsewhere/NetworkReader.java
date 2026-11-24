package org.quarkword.elsewhere;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.quarkword.core.runtime.Binding;
import org.quarkword.core.runtime.SiteBinding;

/** Stands in for a QuarkWord module outside core and persistence that only compares with the network binding. */
@ApplicationScoped
public class NetworkReader {

    @Inject
    SiteBinding binding;

    public boolean network() {
        return binding.current().equals(Binding.NETWORK);
    }
}
