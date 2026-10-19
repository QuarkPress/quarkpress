package org.quarkpress.security.runtime;

import io.quarkus.arc.All;
import io.quarkus.arc.InstanceHandle;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.util.List;
import org.quarkpress.core.Filters;
import org.quarkpress.security.MetaCapabilityFilter;
import org.quarkpress.security.MetaCapabilityNames;

/** The application's mapping: every {@link MetaCapabilityFilter} in order and every {@link MetaCapabilityNames}. */
@ApplicationScoped
public class MetaCapsProducer {

    @Produces
    @Singleton
    MetaCaps metaCaps(@All List<InstanceHandle<MetaCapabilityFilter>> filters, @All List<MetaCapabilityNames> names) {
        return new MetaCaps(Filters.ordered(filters), names);
    }
}
