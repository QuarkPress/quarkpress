package org.quarkpress.multisite.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;
import org.quarkpress.core.OptionChanged;
import org.quarkpress.core.QuarkPressClock;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.core.SiteUpdated;
import org.quarkpress.persistence.NetworkScope;
import org.quarkpress.persistence.runtime.OptionRepository;
import org.quarkpress.persistence.runtime.OptionTransactions;
import org.quarkpress.persistence.runtime.SiteEntity;
import org.quarkpress.persistence.runtime.SiteRepository;

/**
 * Keeps a site's name equal to its {@code blogname} option. Once a transaction that wrote the option has committed,
 * the site id is queued; a virtual thread per site (never two at once for the same site, repeated requests coalesced
 * into one more pass) then renames the site in a network transaction of its own, which fires {@link SiteUpdated}.
 *
 * <p>The rename reads the option's committed value under the network lock and never uses the event's value, so
 * renames of one site converge on the last committed {@code blogname} whatever order the writers' commits finish in,
 * and a write that commits on any thread (an event loop, a scope spawned by a holder of the network lock, a thread
 * with a suspended transaction that holds locks on the site's record) never waits for the rename. The name therefore
 * follows the option a moment after the commit, not within it.
 *
 * <p>Renames still pending when the application stops are lost; {@code blogname} stays the source of truth and the
 * next write to it renames the site. A {@code blogname} that is deleted or not text leaves the name unchanged
 * (deliberate: the name is QuarkPress's denormalized copy; revisit with M3). A failed rename is logged as a warning;
 * the option stays written, and the name stays stale until the next write
 * of {@code blogname}.
 */
@ApplicationScoped
public class BlognameSync {

    private static final Logger LOG = Logger.getLogger(BlognameSync.class);

    static final String BLOGNAME = "blogname";

    @Inject
    NetworkScope network;

    @Inject
    NetworkLocks locks;

    @Inject
    SiteRepository sites;

    @Inject
    OptionRepository options;

    @Inject
    QuarkPressClock clock;

    @Inject
    OptionTransactions transactions;

    @Inject
    Event<SiteUpdated> updated;

    private final Object queueLock = new Object();

    /** Sites that need another pass. */
    private final Set<Long> pending = new HashSet<>();

    /** Sites that have a worker running. */
    private final Set<Long> active = new HashSet<>();

    void onOption(@Observes(during = TransactionPhase.AFTER_SUCCESS) OptionChanged event) {
        if (!event.name().equals(BLOGNAME)) {
            return;
        }
        enqueue(event.site().value());
    }

    private void enqueue(long site) {
        synchronized (queueLock) {
            pending.add(site);
            if (!active.add(site)) {
                return;
            }
        }
        try {
            Thread.ofVirtual().name("quarkpress-blogname-sync").start(() -> drain(site));
        } catch (RuntimeException | Error failure) {
            synchronized (queueLock) {
                active.remove(site);
            }
            LOG.warnf(
                    failure,
                    "site %d keeps its name: the rename after its blogname option changed was not started",
                    site);
        }
    }

    private void drain(long site) {
        try {
            while (true) {
                synchronized (queueLock) {
                    if (!pending.remove(site)) {
                        active.remove(site);
                        return;
                    }
                }
                rename(site);
            }
        } catch (Throwable failure) {
            // an Error (or a failure to activate the request context) ends this worker; the site's slot is freed so
            // that the next blogname write starts another one
            synchronized (queueLock) {
                active.remove(site);
            }
            LOG.warnf(failure, "site %d keeps its name: the rename after its blogname option changed failed", site);
        }
    }

    private void rename(long site) {
        ManagedContext requestContext = Arc.container().requestContext();
        requestContext.activate();
        try {
            NetworkWork.call(network, () -> {
                locks.network();
                Optional<SiteEntity> found = sites.findByIdOptional(site);
                if (found.isEmpty()) {
                    return null;
                }
                Optional<JsonNode> current =
                        options.findOption(SiteId.of(site), BLOGNAME).map(o -> o.value);
                if (current.isEmpty() || !current.get().isTextual()) {
                    return null;
                }
                String name = SiteNames.firstCodePoints(current.get().textValue());
                SiteEntity entity = found.get();
                if (entity.name.equals(name)) {
                    return null;
                }
                SiteInfo before = entity.toInfo();
                entity.name = name;
                Instant now = clock.now().truncatedTo(ChronoUnit.MICROS);
                entity.updatedAt = now;
                sites.flush();
                transactions.publish(updated, new SiteUpdated(SiteId.of(site), now, before, entity.toInfo()));
                return null;
            });
        } catch (RuntimeException failure) {
            LOG.warnf(failure, "site %d keeps its name: renaming it after its blogname option changed failed", site);
        } finally {
            try {
                requestContext.terminate();
            } catch (RuntimeException failure) {
                LOG.debugf(failure, "ending the request context of the rename of site %d failed", site);
            }
        }
    }
}
