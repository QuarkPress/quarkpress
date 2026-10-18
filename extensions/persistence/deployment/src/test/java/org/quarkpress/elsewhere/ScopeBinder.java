package org.quarkpress.elsewhere;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.core.runtime.SiteBinding;

/** Stands in for application code that tries to bind the request the way a scope does. */
@ApplicationScoped
public class ScopeBinder {

    @Inject
    SiteBinding binding;

    public void bindTwo() {
        binding.bindScope(Binding.site(SiteId.of(2)));
    }
}
