package org.quarkpress.core.runtime;

import io.quarkus.arc.Arc;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import java.lang.reflect.Method;
import org.quarkpress.core.SiteFeatureDisabledException;

/**
 * Applies {@code @SiteFeature}: reads the current site on every call and, when the feature is off for it (or no site
 * is resolved, or there is no request context), skips the call the way the build classified the method.
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
        GateKind kind = gate.byClass() ? listed(gate, ctx.getMethod()) : gate.kind();
        if (kind == null || enabled(gate.key())) {
            return ctx.proceed();
        }
        return switch (kind) {
            case OBSERVER -> null;
            case FILTER -> ctx.getParameters()[0];
            case OTHER -> throw new SiteFeatureDisabledException(gate.key());
        };
    }

    /** The kind the build recorded for a method under a class-level gate, or null when the gate does not cover it. */
    private static GateKind listed(SiteFeatureGate gate, Method method) {
        String id = methodId(method);
        String[] methods = gate.methods();
        for (int i = 0; i < methods.length; i++) {
            if (methods[i].equals(id)) {
                return gate.kinds()[i];
            }
        }
        return null;
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

    private boolean enabled(String key) {
        if (!Arc.container().requestContext().isActive()) {
            return false;
        }
        Binding current = binding.current();
        return current.kind() == Binding.Kind.SITE
                && features.forSite(current.site()).isEnabled(key);
    }
}
