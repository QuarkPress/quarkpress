package org.quarkpress.core.runtime;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.util.Optional;

/** Nothing is decided before QuarkPress code runs; replaced by the multisite module's HTTP layer. */
@DefaultBean
@ApplicationScoped
public class NoPresetSite implements PresetSite {

    @Override
    public Optional<Binding> binding() {
        return Optional.empty();
    }

    @Override
    public Optional<URI> redirect() {
        return Optional.empty();
    }
}
