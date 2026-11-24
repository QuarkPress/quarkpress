package org.quarkword.elsewhere;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.quarkword.core.SiteId;
import org.quarkword.core.runtime.Binding;
import org.quarkword.core.runtime.SiteBinding;

/** Stands in for application code that tries to bind the request the way a scope does. */
@ApplicationScoped
public class ScopeBinder {

    @Inject
    SiteBinding binding;

    public void bindTwo() {
        binding.bindScope(Binding.site(SiteId.of(2)));
    }
}
