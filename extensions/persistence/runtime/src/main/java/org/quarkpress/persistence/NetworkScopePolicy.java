package org.quarkpress.persistence;

/** Lets more callers open the network scope, for example callers acting for a super admin. */
public interface NetworkScopePolicy {

    /** Whether {@code caller} (the class calling the network scope, possibly null) may open it. */
    boolean allows(Class<?> caller);
}
