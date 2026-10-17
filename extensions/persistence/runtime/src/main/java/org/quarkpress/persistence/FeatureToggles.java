package org.quarkpress.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.function.Consumer;
import org.quarkpress.core.FeatureRegistry;
import org.quarkpress.core.OptionStore;
import org.quarkpress.core.QuarkPressClock;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteFeatureToggled;
import org.quarkpress.core.SiteId;
import org.quarkpress.persistence.runtime.FeatureOptions;

/**
 * Switches per-site features on and off: for the current site, or as network defaults. A site change fires
 * {@link SiteFeatureToggled} when the site's effective value changes; a network default change fires the network
 * option event.
 */
@ApplicationScoped
public class FeatureToggles {

    @Inject
    FeatureRegistry registry;

    @Inject
    SiteContext site;

    @Inject
    Options options;

    @Inject
    OptionStore store;

    @Inject
    NetworkOptionStore network;

    @Inject
    QuarkPressClock clock;

    @Inject
    Event<SiteFeatureToggled> toggled;

    @Transactional(dontRollbackOn = IllegalArgumentException.class)
    public void setForSite(String key, boolean enabled) {
        changeSite(key, values -> values.put(key, enabled));
    }

    @Transactional(dontRollbackOn = IllegalArgumentException.class)
    public void clearForSite(String key) {
        changeSite(key, values -> values.remove(key));
    }

    @Transactional(dontRollbackOn = IllegalArgumentException.class)
    public void setNetworkDefault(String key, boolean enabled) {
        requireKnown(key);
        ObjectNode values = objectOf(network.find(FeatureOptions.NETWORK));
        values.put(key, enabled);
        network.set(FeatureOptions.NETWORK, values);
    }

    @Transactional(dontRollbackOn = IllegalArgumentException.class)
    public void clearNetworkDefault(String key) {
        requireKnown(key);
        ObjectNode values = objectOf(network.find(FeatureOptions.NETWORK));
        values.remove(key);
        network.set(FeatureOptions.NETWORK, values);
    }

    private void changeSite(String key, Consumer<ObjectNode> edit) {
        requireKnown(key);
        SiteId siteId = site.siteId();
        boolean before = site.features().isEnabled(key);
        ObjectNode values = objectOf(store.find(siteId, FeatureOptions.SITE));
        edit.accept(values);
        options.set(FeatureOptions.SITE, values);
        boolean after = site.features().isEnabled(key);
        if (before != after) {
            toggled.fire(new SiteFeatureToggled(siteId, clock.now().truncatedTo(ChronoUnit.MICROS), key, after));
        }
    }

    private void requireKnown(String key) {
        if (!registry.contains(key)) {
            throw new IllegalArgumentException("unknown QuarkPress feature '" + key + "'");
        }
    }

    private static ObjectNode objectOf(Optional<JsonNode> value) {
        return value.filter(JsonNode::isObject)
                .map(node -> (ObjectNode) node.deepCopy())
                .orElseGet(JsonNodeFactory.instance::objectNode);
    }
}
