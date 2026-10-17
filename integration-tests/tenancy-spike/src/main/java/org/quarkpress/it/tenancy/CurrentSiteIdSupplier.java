package org.quarkpress.it.tenancy;

import io.quarkus.arc.Arc;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.function.Supplier;

/** Supplies the filter parameter: the current site as a number; fails when there is no request context. */
@RegisterForReflection
public class CurrentSiteIdSupplier implements Supplier<Long> {

    @Override
    public Long get() {
        return Long.valueOf(current());
    }

    static String current() {
        if (!Arc.container().requestContext().isActive()) {
            throw new IllegalStateException("no request context: site-scoped data needs a bound site");
        }
        return Arc.container().instance(SpikeTenant.class).get().get();
    }
}
