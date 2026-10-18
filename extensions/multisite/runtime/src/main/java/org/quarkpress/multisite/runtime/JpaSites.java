package org.quarkpress.multisite.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ClientProxy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.persistence.OptimisticLockException;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.hibernate.StaleStateException;
import org.quarkpress.core.CapabilityCheck;
import org.quarkpress.core.FeatureRegistry;
import org.quarkpress.core.NetworkCapabilities;
import org.quarkpress.core.QuarkPressClock;
import org.quarkpress.core.SiteCreated;
import org.quarkpress.core.SiteDeleted;
import org.quarkpress.core.SiteFlagsChanged;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.core.SiteUpdated;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.core.runtime.RequestFeatureCache;
import org.quarkpress.core.runtime.RequestOptionCache;
import org.quarkpress.core.runtime.SiteBinding;
import org.quarkpress.multisite.NewSite;
import org.quarkpress.multisite.SiteChanges;
import org.quarkpress.multisite.SiteFlag;
import org.quarkpress.multisite.Sites;
import org.quarkpress.persistence.NetworkScope;
import org.quarkpress.persistence.runtime.FeatureOptions;
import org.quarkpress.persistence.runtime.OptionEntity;
import org.quarkpress.persistence.runtime.OptionRepository;
import org.quarkpress.persistence.runtime.OptionTransactions;
import org.quarkpress.persistence.runtime.OptionWrites;
import org.quarkpress.persistence.runtime.Scopes;
import org.quarkpress.persistence.runtime.SiteDefaults;
import org.quarkpress.persistence.runtime.SiteEntity;
import org.quarkpress.persistence.runtime.SiteRepository;

/**
 * Sites stored in qp_site. Every write runs in the network scope, takes the network lock first and changes site rows
 * under their version. A change to a site from a transaction bound to that site is refused: the caller's rows of the
 * site hold locks on its record that the separate network transaction would wait for.
 *
 * <p>Waiting for other transactions: the network lock keeps network writes out of each other's way, but a change to a
 * site's row ({@link #update}, the flag changes, {@link #delete}) can still wait for a transaction that inserted rows
 * of that site and has not finished. The insert's foreign key check locks the site's row (shared on MySQL, for key
 * share on PostgreSQL). On MySQL every change of the row waits for it. On PostgreSQL a change waits only when it takes
 * a stronger lock: a delete, or a change of the domain or path (columns of a unique index); a change of the name or
 * a flag does not wait. The change completes once the other transaction ends. If that transaction also needs the
 * network lock (it writes a network option, a feature default or a site, which another network write holds), the
 * two wait for each other: MySQL detects the deadlock and rolls one back, and PostgreSQL does too.
 *
 * <p>Network writes of this class run in a transaction of their own, on their own thread, unless the caller is bound
 * to the network (then they join the caller's). A caller's transaction that already holds the network lock (it wrote a
 * network option or feature default) is refused with {@link IllegalStateException} before the write starts, since the
 * write would wait for the lock the caller holds. So is a change of a site that the caller, or a thread up the spawn
 * chain of the caller's scope, is bound to inside an active transaction: that transaction's rows of the site lock
 * its record, and it waits for the caller.
 *
 * <p>Every network transaction waits for a lock for a bounded time ({@link NetworkLocks#network()}), so a wait that no
 * database sees as a deadlock ends with a lock-timeout failure instead of holding the network lock indefinitely.
 */
@ApplicationScoped
public class JpaSites implements Sites {

    static final int MAX_LIMIT = 1000;

    private static final int ECHO_LENGTH = 64;

    @Inject
    NetworkScope network;

    @Inject
    SiteRepository sites;

    @Inject
    NetworkLocks locks;

    @Inject
    SiteDomainRepository domains;

    @Inject
    OptionRepository options;

    @Inject
    OptionWrites optionWrites;

    @Inject
    FeatureRegistry registry;

    @Inject
    CapabilityCheck capabilities;

    @Inject
    QuarkPressClock clock;

    @Inject
    OptionTransactions transactions;

    @Inject
    SiteBinding binding;

    @Inject
    RequestOptionCache optionCache;

    @Inject
    RequestFeatureCache featureCache;

    @Inject
    Event<SiteCreated> created;

    @Inject
    Event<SiteUpdated> updated;

    @Inject
    Event<SiteFlagsChanged> flagsChanged;

    @Inject
    Event<SiteDeleted> deleted;

    @Override
    public SiteInfo create(NewSite site) {
        Objects.requireNonNull(site, "site");
        capabilities.requireNetwork(NetworkCapabilities.CREATE_SITES);
        String domain = domain(site.domain());
        String path = path(site.path());
        String title = SiteNames.require(site.title());
        ObjectNode switches = switches(site.features());
        SiteInfo info = NetworkWork.call(network, () -> {
            locks.network();
            SiteEntity main = locks.main();
            requireFree(domain, path, null);
            requireNotReserved(domain, path, main);
            Instant now = now();
            SiteEntity entity = SiteEntity.create(domain, path, title, now);
            entity.publicFlag = (short) site.publicFlag();
            sites.persist(entity);
            if (title.isEmpty()) {
                entity.name = "Site " + entity.id;
            }
            sites.flush();
            SiteId id = SiteId.of(entity.id);
            SiteInfo inserted = entity.toInfo();
            // WordPress compares a new site with a default one (public 1, no flags) and fires the status
            // transitions before the site is initialized
            SiteInfo standard = new SiteInfo(id, domain, path, entity.name, 1, false, false, false, false);
            if (!inserted.equals(standard)) {
                transactions.publish(flagsChanged, new SiteFlagsChanged(id, now, standard, inserted));
            }
            for (Map.Entry<String, JsonNode> option :
                    SiteDefaults.forNewSite(entity.name).entrySet()) {
                JsonNode value = option.getKey().equals(FeatureOptions.SITE) ? switches : option.getValue();
                options.persist(OptionEntity.create(id, option.getKey(), value, true, now));
            }
            // flags observers above may have read the new site's switches before its options existed
            forget(id, SiteDefaults.forNewSite("").keySet(), true);
            transactions.publish(created, new SiteCreated(id, now, inserted));
            return inserted;
        });
        // the calling request, if it has one, may be another context than the one the work ran in
        forget(info.id(), SiteDefaults.forNewSite("").keySet(), false);
        return info;
    }

    @Override
    @ActivateRequestContext
    @Transactional
    public Optional<SiteInfo> find(SiteId site) {
        Objects.requireNonNull(site, "site");
        return sites.findByIdOptional(site.value()).map(SiteEntity::toInfo);
    }

    @Override
    @ActivateRequestContext
    @Transactional
    public Optional<SiteInfo> findByAddress(String domain, String path) {
        String normalizedDomain = SiteAddresses.normalizeDomain(domain);
        String normalizedPath = SiteAddresses.normalizePath(path);
        return sites.find("domain = ?1 and path = ?2", normalizedDomain, normalizedPath)
                .firstResultOptional()
                .map(SiteEntity::toInfo);
    }

    @Override
    @ActivateRequestContext
    @Transactional
    public List<SiteInfo> list(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("offset must be at least 0 and limit between 1 and " + MAX_LIMIT);
        }
        // the last index, offset + limit - 1, must be an int
        if (offset > Integer.MAX_VALUE - limit + 1) {
            throw new IllegalArgumentException(
                    "offset must be at most " + (Integer.MAX_VALUE - limit + 1) + " for limit " + limit);
        }
        return sites.find("order by id").range(offset, offset + limit - 1).list().stream()
                .map(SiteEntity::toInfo)
                .toList();
    }

    @Override
    public SiteInfo update(SiteId site, SiteChanges changes) {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(changes, "changes");
        capabilities.requireNetwork(NetworkCapabilities.MANAGE_SITES);
        requireNotFromItsOwnTransaction(site);
        String domain = changes.domain() == null ? null : domain(changes.domain());
        String path = changes.path() == null ? null : path(changes.path());
        String name = changes.name() == null ? null : SiteNames.require(changes.name());
        AtomicBoolean renamedHere = new AtomicBoolean();
        SiteInfo result = NetworkWork.call(network, () -> {
            locks.network();
            SiteEntity main = locks.main();
            boolean isMain = main.id.longValue() == site.value();
            SiteEntity entity = isMain ? main : locks.site(site);
            SiteInfo before = entity.toInfo();
            String newDomain = domain == null ? entity.domain : domain;
            String newPath = path == null ? entity.path : path;
            if (!newDomain.equals(entity.domain) || !newPath.equals(entity.path)) {
                requireFree(newDomain, newPath, entity.id);
                if (!isMain) {
                    requireNotReserved(newDomain, newPath, main);
                }
            }
            entity.domain = newDomain;
            entity.path = newPath;
            boolean renamed = name != null && !name.equals(entity.name);
            if (renamed) {
                entity.name = name;
            }
            SiteInfo after = entity.toInfo();
            if (after.equals(before)) {
                return before;
            }
            Instant now = now();
            entity.updatedAt = now;
            flush(site);
            if (renamed) {
                optionWrites.set(site, "blogname", TextNode.valueOf(name), null);
                renamedHere.set(true);
            }
            transactions.publish(updated, new SiteUpdated(site, now, before, after));
            return after;
        });
        if (renamedHere.get()) {
            // the write dropped the scope's cached blogname; the calling request may have read the old one
            forget(site, List.of("blogname"), false);
        }
        return result;
    }

    @Override
    public SiteInfo setFlag(SiteId site, SiteFlag flag, boolean value) {
        Objects.requireNonNull(flag, "flag");
        return changeFlags(site, entity -> {
            switch (flag) {
                case ARCHIVED -> entity.archived = value;
                case MATURE -> entity.mature = value;
                case SPAM -> entity.spam = value;
                case DELETED -> entity.deleted = value;
            }
        });
    }

    @Override
    public SiteInfo setPublic(SiteId site, int publicFlag) {
        short checked = (short) NewSite.requirePublic(publicFlag);
        return changeFlags(site, entity -> entity.publicFlag = checked);
    }

    /**
     * Deletes a site with all its rows (the database removes the site's rows with its record), then fires
     * {@code SiteDeleted} inside the deleting transaction.
     *
     * <p>The site's options are gone when the event is fired, so a feature-gated observer of {@code SiteDeleted} sees
     * the network defaults for the site's features, not the switches the site had.
     */
    @Override
    public void delete(SiteId site) {
        Objects.requireNonNull(site, "site");
        capabilities.requireNetwork(NetworkCapabilities.DELETE_SITES);
        requireNotFromItsOwnTransaction(site);
        NetworkWork.call(network, () -> {
            locks.network();
            SiteEntity main = locks.main();
            if (main.id.longValue() == site.value()) {
                throw new IllegalArgumentException("the main site cannot be deleted");
            }
            SiteEntity entity = locks.site(site);
            SiteInfo last = entity.toInfo();
            sites.delete(entity);
            flush(site);
            forgetSite(site, true);
            transactions.publish(deleted, new SiteDeleted(site, now(), last));
            return null;
        });
        forgetSite(site, false);
    }

    private SiteInfo changeFlags(SiteId site, Consumer<SiteEntity> edit) {
        Objects.requireNonNull(site, "site");
        capabilities.requireNetwork(NetworkCapabilities.MANAGE_SITES);
        requireNotFromItsOwnTransaction(site);
        return NetworkWork.call(network, () -> {
            locks.network();
            SiteEntity main = locks.main();
            if (main.id.longValue() == site.value()) {
                throw new IllegalArgumentException("the main site's flags cannot change");
            }
            SiteEntity entity = locks.site(site);
            SiteInfo before = entity.toInfo();
            edit.accept(entity);
            SiteInfo after = entity.toInfo();
            if (after.equals(before)) {
                return before;
            }
            Instant now = now();
            entity.updatedAt = now;
            flush(site);
            transactions.publish(updated, new SiteUpdated(site, now, before, after));
            transactions.publish(flagsChanged, new SiteFlagsChanged(site, now, before, after));
            return after;
        });
    }

    /**
     * Refuses to change {@code site} from a transaction of a request bound to it, or from a scope thread spawned
     * (directly or through other scopes) by a thread that was bound to it inside an active transaction: rows of the
     * site written by such a transaction hold locks on the site's record, and the network transaction, which runs
     * separately while the caller (and so the spawner) waits, would wait for them without the database seeing a
     * deadlock.
     */
    private void requireNotFromItsOwnTransaction(SiteId site) {
        if (Scopes.spawnerBoundTo(site)) {
            throw boundTransaction(site);
        }
        if (!Arc.container().requestContext().isActive() || !QuarkusTransaction.isActive()) {
            return;
        }
        Optional<Binding> bound = binding.bound();
        if (bound.isPresent()
                && bound.get().kind() == Binding.Kind.SITE
                && bound.get().site().equals(site)) {
            throw boundTransaction(site);
        }
    }

    private static IllegalStateException boundTransaction(SiteId site) {
        return new IllegalStateException("change site " + site.value() + " outside a transaction bound to it: the"
                + " caller's rows of that site lock its record");
    }

    /**
     * Writes the site's changes now. Every writer of {@code qp_site} holds the network lock and reads the row after
     * taking it, so a version conflict cannot occur for a write in a transaction of its own. A caller bound to the
     * network joins its own transaction: if it read the site before another write changed it, the write works on that
     * stale row and its version no longer matches, which is reported as a concurrent change.
     */
    private void flush(SiteId site) {
        try {
            sites.flush();
        } catch (OptimisticLockException | StaleStateException concurrent) {
            throw new IllegalStateException("site " + site.value() + " changed concurrently; try again", concurrent);
        }
    }

    /**
     * Refuses an address another site has, and a domain whose request forms (with or without {@code www.}) are an
     * alias: the domain resolver, which runs first, would answer those requests with the alias's site.
     */
    private void requireFree(String domain, String path, Long except) {
        Optional<SiteEntity> taken =
                sites.find("domain = ?1 and path = ?2", domain, path).firstResultOptional();
        if (taken.isPresent() && !taken.get().id.equals(except)) {
            throw new IllegalArgumentException("a site already exists at " + domain + path);
        }
        Optional<SiteDomainEntity> alias =
                domains.find("domain in ?1", SiteAddresses.wwwForms(domain)).firstResultOptional();
        if (alias.isPresent()) {
            throw new IllegalArgumentException(
                    "the domain " + alias.get().domain + " is an alias of site " + alias.get().siteId);
        }
    }

    /** The normalized domain; one that normalizes to nothing or to {@code 0} is refused (WordPress: both empty). */
    private static String domain(String given) {
        String domain = SiteAddresses.normalizeDomain(given);
        if (domain.equals("0")) {
            throw new IllegalArgumentException("a site domain must not be empty");
        }
        return domain;
    }

    /** The normalized path; one that no request can reach or that the non-application root covers is refused. */
    private static String path(String given) {
        String path = SiteAddresses.normalizePath(given);
        if (!SiteAddresses.reachablePath(path)) {
            throw new IllegalArgumentException("the site path " + echo(path) + " cannot be reached by requests");
        }
        if (NonApplicationPath.covers(path)) {
            throw new IllegalArgumentException("the site path " + echo(path) + " lies under the non-application root,"
                    + " where requests are not resolved to sites");
        }
        return path;
    }

    private static String echo(String path) {
        return path.length() <= ECHO_LENGTH ? path : path.substring(0, ECHO_LENGTH);
    }

    /**
     * Drops the current request's cached options and feature switches of {@code site} (when a request context is
     * active), now and, with {@code again}, once the transaction completes: reads of a site's options before they were
     * written or after they were deleted must not outlive the change.
     */
    private void forgetSite(SiteId site, boolean again) {
        if (!Arc.container().requestContext().isActive()) {
            return;
        }
        RequestOptionCache requestOptions = ClientProxy.unwrap(optionCache);
        RequestFeatureCache requestFeatures = ClientProxy.unwrap(featureCache);
        Runnable forget = () -> {
            requestOptions.invalidateSite(site);
            requestFeatures.invalidate(site);
        };
        if (again) {
            transactions.afterCompletion(forget);
        }
        forget.run();
    }

    private void forget(SiteId site, Collection<String> names, boolean again) {
        if (!Arc.container().requestContext().isActive()) {
            return;
        }
        RequestOptionCache requestOptions = ClientProxy.unwrap(optionCache);
        RequestFeatureCache requestFeatures = ClientProxy.unwrap(featureCache);
        Runnable forget = () -> {
            names.forEach(name -> requestOptions.invalidate(site, name));
            requestFeatures.invalidate(site);
        };
        if (again) {
            transactions.afterCompletion(forget);
        }
        forget.run();
    }

    private static void requireNotReserved(String domain, String path, SiteEntity main) {
        SiteAddresses.reservedSegment(domain, path, main.domain, main.path).ifPresent(segment -> {
            throw new IllegalArgumentException("the first path segment '" + segment + "' is reserved for WordPress"
                    + " on subdirectory sites of the network; reserved: "
                    + String.join(", ", SiteAddresses.RESERVED_FIRST_SEGMENTS));
        });
    }

    private ObjectNode switches(Map<String, Boolean> features) {
        ObjectNode switches = JsonNodeFactory.instance.objectNode();
        for (Map.Entry<String, Boolean> feature : new TreeMap<>(features).entrySet()) {
            if (registry.find(feature.getKey()).isEmpty()) {
                throw new IllegalArgumentException("unknown QuarkPress feature '" + feature.getKey() + "'");
            }
            switches.put(feature.getKey(), feature.getValue());
        }
        return switches;
    }

    private Instant now() {
        return clock.now().truncatedTo(ChronoUnit.MICROS);
    }
}
