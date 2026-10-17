package org.quarkpress.persistence.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import org.quarkpress.core.FeatureSettings;
import org.quarkpress.core.OptionStore;
import org.quarkpress.core.SiteId;
import org.quarkpress.persistence.NetworkOptions;
import org.quarkpress.persistence.SiteScope;

/**
 * Feature switches kept in the qp_features site option and the qp_feature_defaults network option. The switches of a
 * site are read for that site whatever the request is bound to: for another site the read runs in a scope of its own.
 */
@ApplicationScoped
public class OptionFeatureSettings implements FeatureSettings {

    @Inject
    OptionStore store;

    @Inject
    NetworkOptions network;

    @Inject
    SiteScope scope;

    @Override
    public Map<String, Boolean> siteValues(SiteId site) {
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
