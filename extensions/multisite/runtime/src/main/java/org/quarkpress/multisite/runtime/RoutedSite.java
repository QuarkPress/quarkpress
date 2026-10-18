package org.quarkpress.multisite.runtime;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.runtime.Binding;

/** What the HTTP resolution decided for a request: its binding and, for a redirecting alias, where to redirect. */
public record RoutedSite(Binding binding, Optional<URI> redirect) {

    public RoutedSite {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(redirect, "redirect");
    }
}
