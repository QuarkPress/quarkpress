package org.quarkpress.core.runtime;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.quarkpress.core.SiteNotResolvedException;
import org.quarkpress.core.SiteRequest;

/**
 * The binding of the current request, written once. HTTP site resolution binds it explicitly; otherwise the first read
 * asks the resolvers with {@link SiteRequest#none()} and publishes the answer. A resolver that reads the current site
 * while it is being asked fails with {@link SiteNotResolvedException}.
 *
 * <p>The binding is never rebound: work that must run for another site goes into a fresh request context with its own
 * binding, so a binding that other threads can see does not change.
 */
@RequestScoped
public class SiteBinding {

    @Inject
    SiteResolution resolution;

    private final AtomicReference<Binding> binding = new AtomicReference<>();
    private final Set<Thread> resolvingThreads = ConcurrentHashMap.newKeySet();

    public Binding current() {
        Binding bound = binding.get();
        if (bound != null) {
            return bound;
        }
        Thread self = Thread.currentThread();
        if (!resolvingThreads.add(self)) {
            throw new SiteNotResolvedException("a SiteResolver must not read the current site");
        }
        try {
            Binding resolved =
                    resolution.resolve(SiteRequest.none()).map(Binding::site).orElse(Binding.UNRESOLVED);
            binding.compareAndSet(null, resolved);
        } finally {
            resolvingThreads.remove(self);
        }
        return binding.get();
    }

    public void bind(Binding next) {
        Objects.requireNonNull(next, "binding");
        Binding existing = binding.compareAndExchange(null, next);
        if (existing != null && !existing.equals(next)) {
            throw new IllegalStateException("the request is already bound to " + existing);
        }
    }
}
