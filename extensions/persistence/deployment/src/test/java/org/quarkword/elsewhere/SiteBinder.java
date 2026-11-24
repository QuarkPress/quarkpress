package org.quarkword.elsewhere;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.quarkword.core.SiteId;
import org.quarkword.core.runtime.Binding;
import org.quarkword.core.runtime.SiteBinding;

/** Stands in for a QuarkWord module outside core and persistence that binds the request itself. */
@ApplicationScoped
public class SiteBinder {

    @Inject
    SiteBinding binding;

    public void bindTwo() {
        binding.bind(Binding.site(SiteId.of(2)));
    }
}
