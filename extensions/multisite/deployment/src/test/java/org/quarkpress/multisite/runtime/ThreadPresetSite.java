package org.quarkpress.multisite.runtime;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.net.URI;
import java.util.Optional;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.core.runtime.PresetSite;

/** Binds the requests of the calling thread only, as an HTTP layer binds the request it serves. */
@Alternative
@Priority(1)
@ApplicationScoped
public class ThreadPresetSite implements PresetSite {

    public static final ThreadLocal<Binding> PRESET = new ThreadLocal<>();

    @Override
    public Optional<Binding> binding() {
        return Optional.ofNullable(PRESET.get());
    }

    @Override
    public Optional<URI> redirect() {
        return Optional.empty();
    }
}
