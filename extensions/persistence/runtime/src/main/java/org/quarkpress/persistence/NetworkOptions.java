package org.quarkpress.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ClientProxy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.quarkpress.core.NetworkOptionChanged;
import org.quarkpress.core.QuarkPressClock;
import org.quarkpress.core.runtime.OptionNames;
import org.quarkpress.core.runtime.RequestFeatureCache;
import org.quarkpress.persistence.runtime.FeatureOptions;
import org.quarkpress.persistence.runtime.JsonValues;
import org.quarkpress.persistence.runtime.NetworkOptionEntity;
import org.quarkpress.persistence.runtime.NetworkOptionRepository;
import org.quarkpress.persistence.runtime.OptionTransactions;

/**
 * Options shared by every site of the network. Names and values follow the same rules as site options (see
 * {@link Options}). Reads return copies, and a stored JSON null reads as a {@code NullNode}. A write that changes the
 * value fires {@link NetworkOptionChanged}; an observer that throws rolls the transaction back.
 *
 * <p>A change of the network feature defaults drops the feature values cached by the request that writes it, and
 * again when its transaction completes (commit or rollback). Work running in a scope for another binding has its own
 * request, so such a write does not refresh the feature values the calling request already computed; they are
 * refreshed by its next request.
 */
@ApplicationScoped
public class NetworkOptions {

    @Inject
    NetworkOptionRepository repository;

    @Inject
    QuarkPressClock clock;

    @Inject
    Event<NetworkOptionChanged> changed;

    @Inject
    RequestFeatureCache features;

    @Inject
    OptionTransactions transactions;

    @Transactional
    public Optional<JsonNode> find(String name) {
        return repository.findOption(OptionNames.normalize(name)).map(option -> JsonValues.copyOfStored(option.value));
    }

    /** Sets a value. Returns whether it changed. */
    @Transactional(dontRollbackOn = IllegalArgumentException.class)
    public boolean set(String name, JsonNode value) {
        String normalized = OptionNames.normalize(name);
        JsonNode stored = JsonValues.requireStorable(value);
        Optional<NetworkOptionEntity> existing = repository.findOption(normalized);
        Instant now = now();
        if (existing.isEmpty()) {
            repository.persist(NetworkOptionEntity.create(normalized, stored, now));
            afterChange(normalized, MissingNode.getInstance(), stored, now);
            return true;
        }
        NetworkOptionEntity option = existing.get();
        JsonNode before = JsonValues.copyOfStored(option.value);
        if (JsonValues.sameValue(before, stored)) {
            return false;
        }
        option.value = stored.deepCopy();
        option.updatedAt = now;
        afterChange(normalized, before, stored, now);
        return true;
    }

    /** Deletes the option. Returns whether it existed. */
    @Transactional(dontRollbackOn = IllegalArgumentException.class)
    public boolean delete(String name) {
        String normalized = OptionNames.normalize(name);
        Optional<NetworkOptionEntity> existing = repository.findOption(normalized);
        if (existing.isEmpty()) {
            return false;
        }
        JsonNode before = JsonValues.copyOfStored(existing.get().value);
        repository.delete(existing.get());
        afterChange(normalized, before, MissingNode.getInstance(), now());
        return true;
    }

    private void afterChange(String name, JsonNode before, JsonNode after, Instant at) {
        if (name.equals(FeatureOptions.NETWORK)
                && Arc.container().requestContext().isActive()) {
            RequestFeatureCache requestFeatures = ClientProxy.unwrap(features);
            transactions.afterCompletion(requestFeatures::invalidateAll);
            requestFeatures.invalidateAll();
        }
        transactions.fire(() -> changed.fire(new NetworkOptionChanged(at, name, before, after)));
    }

    private Instant now() {
        return clock.now().truncatedTo(ChronoUnit.MICROS);
    }
}
