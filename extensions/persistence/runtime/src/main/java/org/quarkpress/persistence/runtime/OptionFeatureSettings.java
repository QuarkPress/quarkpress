package org.quarkpress.persistence.runtime;

import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.core.FeatureSettings;
import org.quarkpress.core.OptionStore;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.core.runtime.SiteBinding;
import org.quarkpress.persistence.NetworkOptions;
import org.quarkpress.persistence.SiteScope;

/**
 * Feature switches kept in the qp_features site option and the qp_feature_defaults network option. The switches of a
 * site are read for that site whatever the request is bound to: in the caller's transaction when the request is bound
 * to that site or to the network (so changes the caller has not committed yet count, for example in observers of a
 * site's creation), otherwise in a scope of its own for that site.
 */
@ApplicationScoped
public class OptionFeatureSettings implements FeatureSettings {

    @Inject
    OptionStore store;

    @Inject
    NetworkOptions network;

    @Inject
    SiteScope scope;

    @Inject
    SiteBinding binding;

    @Override
    public Map<String, Boolean> siteValues(SiteId site) {
        if (Arc.container().requestContext().isActive()) {
            Optional<Binding> bound = binding.bound();
            if (bound.isPresent()
                    && (bound.get().kind() == Binding.Kind.NETWORK
                            || site.equals(bound.get().site()))) {
                return FeatureOptions.switches(store.find(site, FeatureOptions.SITE));
            }
        }
        try {
            return scope.call(site, () -> FeatureOptions.switches(store.find(site, FeatureOptions.SITE)));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public Map<String, Boolean> networkDefaults() {
        return FeatureOptions.switches(network.find(FeatureOptions.NETWORK));
    }
}
