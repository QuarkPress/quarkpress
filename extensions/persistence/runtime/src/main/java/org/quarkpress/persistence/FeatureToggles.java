package org.quarkpress.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.quarkpress.core.FeatureDescriptor;
import org.quarkpress.core.FeatureRegistry;
import org.quarkpress.core.QuarkPressClock;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteFeatureToggled;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.runtime.FeatureValues;
import org.quarkpress.persistence.runtime.FeatureOptions;
import org.quarkpress.persistence.runtime.JsonValues;
import org.quarkpress.persistence.runtime.NetworkLockRepository;
import org.quarkpress.persistence.runtime.NetworkOptionRepository;
import org.quarkpress.persistence.runtime.OptionEntity;
import org.quarkpress.persistence.runtime.OptionRepository;
import org.quarkpress.persistence.runtime.OptionTransactions;

/**
 * Switches per-site features on and off: for the current site, or as network defaults. A site change fires
 * {@link SiteFeatureToggled} when the site's effective value changes; a network default change fires the network
 * option event. An observer that throws rolls the change back, whatever the exception type.
 *
 * <p>Each change works on the stored switches: an existing {@code qp_features} option (or the
 * {@code qp_feature_defaults} network option) is locked until the transaction ends, so concurrent changes of different
 * keys do not overwrite each other; a missing site option is inserted only if it is still missing, and a change that
 * loses that race applies itself to the winner's switches. Network default changes lock {@code qp_network_lock} first.
 * The site's {@code qp_site} row is never locked. The effective values before and after a site change are computed from
 * the stored switches, never from the request's cached feature values.
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
    OptionRepository siteOptions;

    @Inject
    NetworkLockRepository networkLock;

    @Inject
    NetworkOptions network;

    @Inject
    NetworkOptionRepository networkOptions;

    @Inject
    QuarkPressClock clock;

    @Inject
    Event<SiteFeatureToggled> toggled;

    @Inject
    OptionTransactions transactions;

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
        changeNetwork(key, values -> values.put(key, enabled));
    }

    @Transactional(dontRollbackOn = IllegalArgumentException.class)
    public void clearNetworkDefault(String key) {
        changeNetwork(key, values -> values.remove(key));
    }

    private void changeSite(String key, Consumer<ObjectNode> edit) {
        FeatureDescriptor feature = requireKnown(key);
        SiteId siteId = site.siteId();
        Map<String, Boolean> defaults = FeatureOptions.switches(network.find(FeatureOptions.NETWORK));
        Optional<OptionEntity> row = siteOptions.lockOption(siteId, FeatureOptions.SITE);
        if (row.isEmpty()) {
            ObjectNode values = JsonNodeFactory.instance.objectNode();
            boolean before = FeatureValues.resolve(feature, Map.of(), defaults).enabled();
            edit.accept(values);
            boolean after = FeatureValues.resolve(feature, FeatureOptions.switches(Optional.of(values)), defaults)
                    .enabled();
            if (options.add(FeatureOptions.SITE, values, true)) {
                toggled(siteId, key, before, after);
                return;
            }
            row = siteOptions.findOptionForUpdate(siteId, FeatureOptions.SITE);
            if (row.isEmpty()) {
                throw new IllegalStateException("the feature switches of site " + siteId.value()
                        + " were deleted while they were changed; try again");
            }
        }
        ObjectNode values = objectOf(Optional.of(JsonValues.copyOfStored(row.get().value)));
        boolean before = FeatureValues.resolve(feature, FeatureOptions.switches(Optional.of(values)), defaults)
                .enabled();
        edit.accept(values);
        boolean after = FeatureValues.resolve(feature, FeatureOptions.switches(Optional.of(values)), defaults)
                .enabled();
        options.set(FeatureOptions.SITE, values);
        toggled(siteId, key, before, after);
    }

    private void toggled(SiteId siteId, String key, boolean before, boolean after) {
        if (before != after) {
            SiteFeatureToggled event =
                    new SiteFeatureToggled(siteId, clock.now().truncatedTo(ChronoUnit.MICROS), key, after);
            transactions.publish(toggled, event);
        }
    }

    private void changeNetwork(String key, Consumer<ObjectNode> edit) {
        requireKnown(key);
        networkLock.lock();
        ObjectNode values = objectOf(networkOptions
                .findOptionForUpdate(FeatureOptions.NETWORK)
                .map(option -> JsonValues.copyOfStored(option.value)));
        edit.accept(values);
        network.set(FeatureOptions.NETWORK, values);
    }

    private FeatureDescriptor requireKnown(String key) {
        return registry.find(key)
                .orElseThrow(() -> new IllegalArgumentException("unknown QuarkPress feature '" + key + "'"));
    }

    private static ObjectNode objectOf(Optional<JsonNode> value) {
        return value.filter(JsonNode::isObject)
                .map(node -> (ObjectNode) node.deepCopy())
                .orElseGet(JsonNodeFactory.instance::objectNode);
    }
}
