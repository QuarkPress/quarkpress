package org.quarkpress.persistence.runtime;

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
import org.quarkpress.core.SiteId;
import org.quarkpress.core.runtime.RequestFeatureCache;
import org.quarkpress.core.runtime.RequestOptionCache;

/**
 * Writes the options of a given site, with the rules of {@code Options}; usable from the network scope. Names must be
 * normalized and values checked with {@link JsonValues#requireStorable} by the caller. Not for application code.
 *
 * <p>The request must be bound to the written site or to the network, as the listener requires for site-scoped rows:
 * the inserts are native SQL, which neither the site filter nor the listener sees.
 *
 * <p>Concurrent writers never fail on the option's unique key: a missing option is inserted only if it is still
 * missing (a writer that loses that race updates the row the winner inserted), and an existing option is locked before
 * it is compared and changed. No write locks the site's {@code qp_site} row, so option writes do not wait for, or
 * deadlock with, other transactions that insert rows of the same site.
 */
@ApplicationScoped
public class OptionWrites {

    @Inject
    OptionRepository repository;

    @Inject
    OptionInserts inserts;

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
     * Like {@code update_option}: a new option takes {@code autoload} (autoloaded when null); an existing one whose
     * value changes takes it when it is not null. Returns whether the value changed.
     */
    @Transactional(dontRollbackOn = IllegalArgumentException.class)
    public boolean set(SiteId site, String name, JsonNode stored, Boolean autoload) {
        requireWritable(site);
        Instant now = now();
        Optional<OptionEntity> existing = repository.lockOption(site, name);
        if (existing.isEmpty()) {
            if (inserts.insertIfAbsent(site, name, stored, autoload == null || autoload, now)) {
                afterChange(site, name, MissingNode.getInstance(), stored, now);
                return true;
            }
            existing = repository.findOptionForUpdate(site, name);
            if (existing.isEmpty()) {
                throw new IllegalStateException("option '" + name + "' of site " + site.value()
                        + " was deleted while it was written; try again");
            }
        }
        OptionEntity option = existing.get();
        JsonNode before = JsonValues.copyOfStored(option.value);
        if (JsonValues.sameValue(before, stored)) {
            return false;
        }
        option.value = stored.deepCopy();
        if (autoload != null) {
            option.autoload = autoload;
        }
        option.updatedAt = now;
        afterChange(site, name, before, stored, now);
        return true;
    }

    /** Like {@code add_option}: returns false and changes nothing when the option exists. */
    @Transactional(dontRollbackOn = IllegalArgumentException.class)
    public boolean add(SiteId site, String name, JsonNode stored, boolean autoload) {
        requireWritable(site);
        Instant now = now();
        if (!inserts.insertIfAbsent(site, name, stored, autoload, now)) {
            return false;
        }
        afterChange(site, name, MissingNode.getInstance(), stored, now);
        return true;
    }

    /** Like {@code wp_set_option_autoload}: returns whether the flag changed; fires no event. */
    @Transactional(dontRollbackOn = IllegalArgumentException.class)
    public boolean setAutoload(SiteId site, String name, boolean autoload) {
        requireWritable(site);
        Optional<OptionEntity> existing = repository.lockOption(site, name);
        if (existing.isEmpty() || existing.get().autoload == autoload) {
            return false;
        }
        OptionEntity option = existing.get();
        option.autoload = autoload;
        option.updatedAt = now();
        forgetCached(site, name);
        return true;
    }

    /** Like {@code delete_option}: returns whether the option existed. */
    @Transactional(dontRollbackOn = IllegalArgumentException.class)
    public boolean delete(SiteId site, String name) {
        requireWritable(site);
        Optional<OptionEntity> existing = repository.lockOption(site, name);
        if (existing.isEmpty()) {
            return false;
        }
        JsonNode before = JsonValues.copyOfStored(existing.get().value);
        repository.delete(existing.get());
        afterChange(site, name, before, MissingNode.getInstance(), now());
        return true;
    }

    private void afterChange(SiteId site, String name, JsonNode before, JsonNode after, Instant at) {
        forgetCached(site, name);
        transactions.fire(() -> changed.fire(new OptionChanged(site, at, name, before, after)));
    }

    /** Drops the request's cached reads now, and again when the transaction completes. */
    private void forgetCached(SiteId site, String name) {
        RequestOptionCache requestCache = ClientProxy.unwrap(cache);
        RequestFeatureCache requestFeatures = name.equals(FeatureOptions.SITE) ? ClientProxy.unwrap(features) : null;
        Runnable forget = () -> {
            requestCache.invalidate(site, name);
            if (requestFeatures != null) {
                requestFeatures.invalidate(site);
            }
        };
        transactions.afterCompletion(forget);
        forget.run();
    }

    private Instant now() {
        return clock.now().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Refuses a write of {@code site}'s options unless the request is bound to that site or to the network, with the
     * listener's messages. Without a request context the site filter's supplier refuses first.
     */
    static void requireWritable(SiteId site) {
        String tenant = CurrentSiteIdSupplier.current();
        if (TenantIds.UNRESOLVED.equals(tenant)) {
            throw new IllegalStateException("no site is bound: a site-scoped row needs a site");
        }
        if (!TenantIds.NETWORK.equals(tenant) && !TenantIds.of(site).equals(tenant)) {
            throw new IllegalStateException("option of site " + site.value() + " written under site " + tenant);
        }
    }
}
