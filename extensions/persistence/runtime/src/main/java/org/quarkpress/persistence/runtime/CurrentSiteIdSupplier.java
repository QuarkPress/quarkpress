package org.quarkpress.persistence.runtime;

import io.quarkus.arc.Arc;
import java.util.function.Supplier;
import org.quarkpress.core.runtime.SiteBinding;

/** The site filter's parameter: the current site id, -1 for the network, 0 for none; fails without a request context. */
public class CurrentSiteIdSupplier implements Supplier<Long> {

    @Override
    public Long get() {
        return Long.valueOf(current());
    }

    static String current() {
        if (!Arc.container().requestContext().isActive()) {
            throw new IllegalStateException("no request context: site-scoped data needs a bound site");
        }
        return TenantIds.of(Arc.container().instance(SiteBinding.class).get().current());
    }
}
