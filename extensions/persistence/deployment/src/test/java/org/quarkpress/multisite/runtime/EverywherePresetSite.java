package org.quarkpress.multisite.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.core.runtime.PresetSite;

/** A careless preset that answers on every thread, scope threads included. */
@ApplicationScoped
public class EverywherePresetSite implements PresetSite {

    public static final AtomicReference<Binding> PRESET = new AtomicReference<>();

    @Override
    public Optional<Binding> binding() {
        return Optional.ofNullable(PRESET.get());
    }

    @Override
    public Optional<URI> redirect() {
        return Optional.empty();
    }
}
