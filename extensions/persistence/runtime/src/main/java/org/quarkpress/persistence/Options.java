package org.quarkpress.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import io.quarkus.arc.ClientProxy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.quarkpress.core.OptionChanged;
import org.quarkpress.core.QuarkPressClock;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.runtime.OptionNames;
import org.quarkpress.core.runtime.RequestFeatureCache;
import org.quarkpress.core.runtime.RequestOptionCache;
import org.quarkpress.persistence.runtime.FeatureOptions;
import org.quarkpress.persistence.runtime.JsonValues;
import org.quarkpress.persistence.runtime.OptionEntity;
import org.quarkpress.persistence.runtime.OptionRepository;
import org.quarkpress.persistence.runtime.OptionTransactions;

/**
 * Writes the current site's options; read them through {@code SiteContext.options()}. The methods behave like
 * WordPress's {@code update_option}, {@code add_option}, {@code delete_option} and {@code wp_set_option_autoload}.
 *
 * <p>Names are trimmed like WordPress trims them, and an empty name or {@code "0"} is refused. Values follow the rules
 * of {@link JsonValues}; numbers compare by value and object keys in order. A write that creates, changes or deletes a
 * value fires {@link OptionChanged} inside the transaction; an observer that throws rolls the transaction back. An
 * invalid name or value throws {@link IllegalArgumentException} without marking the caller's transaction for rollback.
 * When the transaction rolls back, the request's cached reads of the option are dropped.
 */
@ApplicationScoped
public class Options {

    @Inject
    SiteContext site;

    @Inject
    OptionRepository repository;

    @Inject
    QuarkPressClock clock;

    @Inject
    Event<OptionChanged> changed;

    @Inject
    RequestOptionCache cache;

    @Inject
    RequestFeatureCache features;

    @Inject
    OptionTransactions transactions;

    /**
     * Sets a value, like {@code update_option}: a new option is autoloaded. Returns whether the value changed; an
     * unchanged value returns false and changes nothing.
     */
    @Transactional(dontRollbackOn = IllegalArgumentException.class)
    public boolean set(String name, JsonNode value) {
        return write(name, value, null);
    }

    /**
     * Sets a value, like {@code update_option} with an autoload argument: a new option takes {@code autoload}, and so
     * does an existing one whose value changes. An unchanged value returns false and keeps the autoload flag; use
     * {@link #setAutoload} to change only the flag.
     */
    @Transactional(dontRollbackOn = IllegalArgumentException.class)
    public boolean set(String name, JsonNode value, boolean autoload) {
        return write(name, value, autoload);
    }

    /**
     * Adds an option, like {@code add_option}: returns false and changes nothing when the option already exists.
     */
    @Transactional(dontRollbackOn = IllegalArgumentException.class)
    public boolean add(String name, JsonNode value, boolean autoload) {
        String normalized = OptionNames.normalize(name);
        JsonNode stored = JsonValues.requireStorable(value);
        SiteId siteId = site.siteId();
        if (repository.findOption(siteId, normalized).isPresent()) {
            return false;
        }
        insert(siteId, normalized, stored, autoload);
        return true;
    }

    /**
     * Changes only the autoload flag, like {@code wp_set_option_autoload}. Returns whether it changed; false when the
     * option does not exist. Fires no event.
     */
    @Transactional(dontRollbackOn = IllegalArgumentException.class)
    public boolean setAutoload(String name, boolean autoload) {
        String normalized = OptionNames.normalize(name);
        SiteId siteId = site.siteId();
        Optional<OptionEntity> existing = repository.findOption(siteId, normalized);
        if (existing.isEmpty() || existing.get().autoload == autoload) {
            return false;
        }
        OptionEntity option = existing.get();
        option.autoload = autoload;
        option.updatedAt = now();
        forgetCached(siteId, normalized);
        return true;
    }

    /** Deletes the option. Returns whether it existed. */
    @Transactional(dontRollbackOn = IllegalArgumentException.class)
    public boolean delete(String name) {
        String normalized = OptionNames.normalize(name);
        SiteId siteId = site.siteId();
        Optional<OptionEntity> existing = repository.findOption(siteId, normalized);
        if (existing.isEmpty()) {
            return false;
        }
        JsonNode before = JsonValues.copyOfStored(existing.get().value);
        repository.delete(existing.get());
        afterChange(siteId, normalized, before, MissingNode.getInstance(), now());
        return true;
    }

    private boolean write(String name, JsonNode value, Boolean autoload) {
        String normalized = OptionNames.normalize(name);
        JsonNode stored = JsonValues.requireStorable(value);
        SiteId siteId = site.siteId();
        Optional<OptionEntity> existing = repository.findOption(siteId, normalized);
        if (existing.isEmpty()) {
            insert(siteId, normalized, stored, autoload == null || autoload);
            return true;
        }
        OptionEntity option = existing.get();
        JsonNode before = JsonValues.copyOfStored(option.value);
        if (JsonValues.sameValue(before, stored)) {
            return false;
        }
        Instant now = now();
        option.value = stored.deepCopy();
        if (autoload != null) {
            option.autoload = autoload;
        }
        option.updatedAt = now;
        afterChange(siteId, normalized, before, stored, now);
        return true;
    }

    private void insert(SiteId siteId, String name, JsonNode stored, boolean autoload) {
        Instant now = now();
        repository.persist(OptionEntity.create(siteId, name, stored, autoload, now));
        afterChange(siteId, name, MissingNode.getInstance(), stored, now);
    }

    private void afterChange(SiteId siteId, String name, JsonNode before, JsonNode after, Instant at) {
        forgetCached(siteId, name);
        transactions.fire(() -> changed.fire(new OptionChanged(siteId, at, name, before, after)));
    }

    /** Drops the request's cached reads now, and again if the transaction does not commit. */
    private void forgetCached(SiteId siteId, String name) {
        RequestOptionCache requestCache = ClientProxy.unwrap(cache);
        boolean featureOption = name.equals(FeatureOptions.SITE);
        RequestFeatureCache requestFeatures = featureOption ? ClientProxy.unwrap(features) : null;
        Runnable forget = () -> {
            requestCache.invalidate(siteId, name);
            if (requestFeatures != null) {
                requestFeatures.invalidate(siteId);
            }
        };
        transactions.onRollback(forget);
        forget.run();
    }

    private Instant now() {
        return clock.now().truncatedTo(ChronoUnit.MICROS);
    }
}
