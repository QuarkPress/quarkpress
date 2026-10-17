package org.quarkpress.elsewhere;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.core.runtime.SiteBinding;

/** Stands in for a QuarkPress module outside core and persistence that binds the network through a method reference. */
@ApplicationScoped
public class NetworkBinder {

    @Inject
    SiteBinding binding;

    public void bindNetwork() {
        Optional.of(Binding.NETWORK).ifPresent(binding::bind);
    }
}
