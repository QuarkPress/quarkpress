package org.quarkpress.security.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

/**
 * Reaches the package-private capability reads from tests, as security module code does. Test classes are loaded by
 * another class loader than the runtime module, so package access does not reach across; the probe calls the reads
 * reflectively (through the bean's proxy, so its interceptors apply).
 */
@ApplicationScoped
public class ReadsProbe {

    @Inject
    CapabilityReads reads;

    @SuppressWarnings("unchecked")
    public List<CapabilityReads.StoredMembership> memberships(UserId user) {
        return (List<CapabilityReads.StoredMembership>) invoke("memberships", new Class<?>[] {UserId.class}, user);
    }

    @SuppressWarnings("unchecked")
    public Optional<CapabilityReads.StoredMembership> membership(SiteId site, UserId user) {
        return (Optional<CapabilityReads.StoredMembership>)
                invoke("membership", new Class<?>[] {SiteId.class, UserId.class}, site, user);
    }

    private Object invoke(String name, Class<?>[] types, Object... args) {
        try {
            Method method = CapabilityReads.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(reads, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
