package org.quarkpress.core.runtime;

import io.quarkus.arc.Arc;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import java.lang.reflect.Method;
import org.quarkpress.core.SiteEvent;
import org.quarkpress.core.SiteFeatureDisabledException;

/**
 * Applies {@code @SiteFeature}: on every call it checks the feature and, when it is off, skips the call the way the
 * build classified the method. An observer of a {@link SiteEvent} checks the event's site; every other method checks
 * the current site, and counts as disabled when no site is resolved or there is no request context.
 *
 * <p>Ordering: the priority is {@link #PRIORITY}, after the security interceptors ({@code PLATFORM_BEFORE + 150}),
 * so a caller without access is refused before the feature is checked, and before the transaction interceptors
 * ({@code PLATFORM_BEFORE + 200}), so a disabled feature never opens a transaction.
 */
@SiteFeatureGate
@Interceptor
@Priority(SiteFeatureInterceptor.PRIORITY)
public class SiteFeatureInterceptor {

    /** Between security (+150) and transactions (+200). */
    public static final int PRIORITY = Interceptor.Priority.PLATFORM_BEFORE + 175;

    @Inject
    SiteBinding binding;

    @Inject
    FeatureResolver features;

    @AroundInvoke
    Object gate(InvocationContext ctx) throws Exception {
        SiteFeatureGate gate = ctx.getInterceptorBinding(SiteFeatureGate.class);
        if (gate == null) {
            throw new IllegalStateException("QuarkPress feature gate without its binding on " + ctx.getMethod());
        }
        GateKind kind;
        int eventParameter;
        if (gate.byClass()) {
            int index = indexOf(gate, ctx.getMethod());
            if (index < 0) {
                return ctx.proceed();
            }
            kind = gate.kinds()[index];
            if (index >= gate.eventParameters().length) {
                throw new IllegalStateException("QuarkPress feature gate without the observed parameter of "
                        + ctx.getMethod() + " (the build wrote " + gate.eventParameters().length
                        + " positions for " + gate.methods().length + " methods)");
            }
            eventParameter = gate.eventParameters()[index];
        } else {
            kind = gate.kind();
            eventParameter = gate.eventParameter();
        }
        if (enabled(gate.key(), kind, eventParameter, ctx.getParameters())) {
            return ctx.proceed();
        }
        return switch (kind) {
            case OBSERVER -> null;
            case FILTER -> ctx.getParameters()[0];
            case RESOURCE -> throw new SiteFeatureNotFoundException(gate.key());
            case OTHER -> throw new SiteFeatureDisabledException(gate.key());
        };
    }

    /** The index the build recorded for a method under a class-level gate, or -1 when the gate does not cover it. */
    private static int indexOf(SiteFeatureGate gate, Method method) {
        String id = methodId(method);
        String[] methods = gate.methods();
        for (int i = 0; i < methods.length; i++) {
            if (methods[i].equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /** {@code name(parameter,types)} with erased parameter type names, as the build writes them. */
    static String methodId(Method method) {
        StringBuilder id = new StringBuilder(method.getName()).append('(');
        Class<?>[] parameters = method.getParameterTypes();
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) {
                id.append(',');
            }
            id.append(parameters[i].getName());
        }
        return id.append(')').toString();
    }

    private boolean enabled(String key, GateKind kind, int eventParameter, Object[] parameters) {
        if (kind == GateKind.OBSERVER
                && eventParameter >= 0
                && eventParameter < parameters.length
                && parameters[eventParameter] instanceof SiteEvent event) {
            return features.forSite(event.site()).isEnabled(key);
        }
        if (!Arc.container().requestContext().isActive()) {
            return false;
        }
        Binding current = binding.current();
        return current.kind() == Binding.Kind.SITE
                && features.forSite(current.site()).isEnabled(key);
    }
}
