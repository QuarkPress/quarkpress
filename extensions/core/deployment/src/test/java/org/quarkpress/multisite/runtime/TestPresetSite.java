package org.quarkpress.multisite.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.core.runtime.PresetSite;

/**
 * Stands in for the multisite HTTP layer in core's tests: a normal bean in the one package allowed to replace the
 * default preset. Tests set what it returns.
 */
@ApplicationScoped
public class TestPresetSite implements PresetSite {

    public static final AtomicReference<Binding> PRESET = new AtomicReference<>();
    public static final AtomicReference<URI> REDIRECT = new AtomicReference<>();

    /** Runs each time the preset is asked, so a test can make it misbehave. */
    public static final AtomicReference<Runnable> ON_ASK = new AtomicReference<>();

    @Override
    public Optional<Binding> binding() {
        // Like the real HTTP layer, it knows nothing about a scope's own thread.
        if (Thread.currentThread().getName().equals("quarkpress-scope")) {
            return Optional.empty();
        }
        Runnable hook = ON_ASK.get();
        if (hook != null) {
            hook.run();
        }
        return Optional.ofNullable(PRESET.get());
    }

    @Override
    public Optional<URI> redirect() {
        return Optional.ofNullable(REDIRECT.get());
    }
}
