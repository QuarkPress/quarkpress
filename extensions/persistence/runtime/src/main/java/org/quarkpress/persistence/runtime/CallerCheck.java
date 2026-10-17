package org.quarkpress.persistence.runtime;

import java.util.List;
import org.quarkpress.persistence.NetworkScopePolicy;

/** Finds who called a scope, for the network scope's caller rule. */
public final class CallerCheck {

    static final List<String> TRUSTED_PACKAGES = List.of(
            "org.quarkpress.multisite.runtime.",
            "org.quarkpress.security.runtime.",
            "org.quarkpress.scheduling.runtime.");

    /** Suffixes of the classes ArC generates for a bean: its client proxy and its interception subclass. */
    static final List<String> GENERATED_SUFFIXES = List.of("_ClientProxy", "_Subclass");

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private CallerCheck() {}

    /**
     * The first class on the stack that is neither this class, nor {@code scope}, nor a class ArC generated for it
     * ({@code scope}'s exact name followed by {@code _ClientProxy} or {@code _Subclass}); null if there is none.
     */
    public static Class<?> callerOf(Class<?> scope) {
        String name = scope.getName();
        return WALKER.walk(frames -> frames.map(StackWalker.StackFrame::getDeclaringClass)
                .filter(type -> type != CallerCheck.class && !isScopeClass(type.getName(), name))
                .findFirst()
                .orElse(null));
    }

    private static boolean isScopeClass(String candidate, String scope) {
        if (candidate.equals(scope)) {
            return true;
        }
        for (String suffix : GENERATED_SUFFIXES) {
            if (candidate.equals(scope + suffix)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isTrustedModule(Class<?> caller) {
        if (caller == null) {
            return false;
        }
        for (String prefix : TRUSTED_PACKAGES) {
            if (caller.getName().startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code caller} (possibly null) may open the network scope: a trusted module, or a policy allows it. */
    public static boolean mayOpenNetwork(Class<?> caller, Iterable<? extends NetworkScopePolicy> policies) {
        if (isTrustedModule(caller)) {
            return true;
        }
        for (NetworkScopePolicy policy : policies) {
            if (policy.allows(caller)) {
                return true;
            }
        }
        return false;
    }
}
