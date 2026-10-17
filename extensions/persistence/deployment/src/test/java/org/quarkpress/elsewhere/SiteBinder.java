package org.quarkpress.elsewhere;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.core.runtime.SiteBinding;

/** Stands in for a QuarkPress module outside core and persistence that binds the request itself. */
@ApplicationScoped
public class SiteBinder {

    @Inject
    SiteBinding binding;

    public void bindTwo() {
        binding.bind(Binding.site(SiteId.of(2)));
    }
}
