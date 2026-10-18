package org.quarkpress.core.runtime;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.quarkpress.core.SiteNotResolvedException;
import org.quarkpress.core.SiteRequest;

/**
 * The binding of the current request, written once. When an HTTP layer decided the request's site before any
 * QuarkPress code ran ({@link PresetSite}), that decision is the binding. Otherwise the first read asks the resolvers
 * with {@link SiteRequest#none()} and publishes the answer. A resolver that reads the current site while it is being
 * asked fails with {@link SiteNotResolvedException}.
 *
 * <p>The binding is never rebound: work that must run for another site goes into a fresh request context with its own
 * binding, so a binding that other threads can see does not change.
 */
@RequestScoped
public class SiteBinding {

    @Inject
    SiteResolution resolution;

    @Inject
    PresetSite preset;

    private static final String SCOPES = "org.quarkpress.persistence.runtime.Scopes";
    private static final StackWalker SCOPE_CALLER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private final AtomicReference<Binding> binding = new AtomicReference<>();
    private final Set<Thread> resolvingThreads = ConcurrentHashMap.newKeySet();
    private final Set<Thread> presetThreads = ConcurrentHashMap.newKeySet();

    public Binding current() {
        Optional<Binding> bound = bound();
        if (bound.isPresent()) {
            return bound.get();
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

    /**
     * The binding if the request is already bound, or if an HTTP layer decided it before QuarkPress code ran (that
     * decision is adopted). Never asks the resolvers. An HTTP layer may decide a site or "no site", never the network:
     * the network scope opens only through {@code NetworkScope}, whose callers are checked.
     */
    public Optional<Binding> bound() {
        Binding bound = binding.get();
        if (bound != null) {
            return Optional.of(bound);
        }
        Optional<Binding> decided = decided();
        if (decided.isEmpty()) {
            return Optional.empty();
        }
        if (decided.get().kind() == Binding.Kind.NETWORK) {
            throw new IllegalStateException("an HTTP layer cannot bind a request to the network");
        }
        binding.compareAndSet(null, decided.get());
        return Optional.of(binding.get());
    }

    /** This class and the classes ArC generates for it (client proxy, interception subclass). */
    private static boolean isOwnClass(String name) {
        String own = SiteBinding.class.getName();
        return name.equals(own) || name.equals(own + "_ClientProxy") || name.equals(own + "_Subclass");
    }

    private Optional<Binding> decided() {
        Thread self = Thread.currentThread();
        if (!presetThreads.add(self)) {
            throw new SiteNotResolvedException("a PresetSite must not read the current site");
        }
        try {
            return preset.binding();
        } finally {
            presetThreads.remove(self);
        }
    }

    public void bind(Binding next) {
        Objects.requireNonNull(next, "binding");
        bound();
        Binding existing = binding.compareAndExchange(null, next);
        if (existing != null && !existing.equals(next)) {
            throw new IllegalStateException("the request is already bound to " + existing);
        }
    }

    /**
     * Binds a request context that a scope has just activated for its work, without consulting the {@link PresetSite}:
     * the scope's target is the binding by definition, whatever an HTTP layer decided for the request that opened the
     * scope. For scopes only: any other caller is refused.
     */
    public void bindScope(Binding next) {
        Objects.requireNonNull(next, "binding");
        Class<?> caller = SCOPE_CALLER.walk(frames -> frames.map(StackWalker.StackFrame::getDeclaringClass)
                .filter(type -> !isOwnClass(type.getName()))
                .findFirst()
                .orElse(null));
        if (caller == null || !SCOPES.equals(caller.getName())) {
            throw new IllegalStateException("only a scope binds its own request context");
        }
        Binding existing = binding.compareAndExchange(null, next);
        if (existing != null && !existing.equals(next)) {
            throw new IllegalStateException("the request is already bound to " + existing);
        }
    }
}
