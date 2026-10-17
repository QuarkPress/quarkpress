package org.quarkpress.persistence.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.core.FeatureSettings;
import org.quarkpress.core.OptionStore;
import org.quarkpress.core.SiteId;
import org.quarkpress.persistence.NetworkOptionStore;
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
    NetworkOptionStore network;

    @Inject
    SiteScope scope;

    @Override
    public Map<String, Boolean> siteValues(SiteId site) {
        try {
            return scope.call(site, () -> booleans(store.find(site, FeatureOptions.SITE)));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public Map<String, Boolean> networkDefaults() {
        return booleans(network.find(FeatureOptions.NETWORK));
    }

    static Map<String, Boolean> booleans(Optional<JsonNode> value) {
        if (value.isEmpty() || !value.get().isObject()) {
            return Map.of();
        }
        Map<String, Boolean> switches = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : value.get().properties()) {
            if (entry.getValue().isBoolean()) {
                switches.put(entry.getKey(), entry.getValue().booleanValue());
            }
        }
        return switches;
    }
}
