package org.quarkpress.multisite.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.util.Optional;
import org.quarkpress.core.deployment.resolution.ForeignPresetInterceptorTest.Watched;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.core.runtime.PresetSite;

/** A preset in the trusted package that application code tries to intercept. */
@ApplicationScoped
@Watched
public class InterceptedPresetSite implements PresetSite {

    @Override
    public Optional<Binding> binding() {
        return Optional.empty();
    }

    @Override
    public Optional<URI> redirect() {
        return Optional.empty();
    }
}
