package org.quarkpress.scheduling.runtime;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.jboss.logging.Logger;
import org.quarkpress.core.PostId;
import org.quarkpress.core.QuarkPressClock;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.persistence.NetworkScope;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;
import org.quarkpress.posts.runtime.PostDates;
import org.quarkpress.posts.runtime.PostEntity;
import org.quarkpress.posts.runtime.PostLocks;
import org.quarkpress.posts.runtime.PostsConfig;
import org.quarkpress.scheduling.SchedulingFeature;
import org.quarkpress.scheduling.SchedulingStatus.Failure;
import org.quarkpress.security.SystemIdentity;

/**
 * The housekeeping job: the post purges WordPress's cron runs daily. For every site of the network in id order, a
 * site with scheduling off is skipped; else first the trash is purged (every trashed post, of any type, whose trash
 * time is strictly older than now minus the trash days), then the stale auto-drafts (every auto-draft last modified
 * more than seven days ago). The candidates of a purge are selected in one read-only site transaction, which ends
 * before any delete; each candidate is then read again under the site's post lock, checked and deleted in a site
 * scope and transaction of its own, as the system identity, so that no restore or save lands between the check and
 * the delete. A candidate gone, restored or edited meanwhile is skipped; a delete that fails is logged and recorded,
 * and the purge goes on with the next candidate. A trashed post without a trash time is never purged.
 */
@ApplicationScoped
public class Housekeeping {

    public static final String IDENTITY = "quarkpress-scheduling-housekeeping";

    private static final Logger LOG = Logger.getLogger(Housekeeping.class);

    /** What one run purged: trashed posts and auto-drafts, over every site. */
    public record Result(int trashPurged, int autoDraftsPurged) {}

    @Inject
    SchedulingConfig config;

    @Inject
    PostsConfig postsConfig;

    @Inject
    SchedulingStatusBean status;

    @Inject
    QuarkPressClock clock;

    @Inject
    NetworkScope network;

    @Inject
    SiteScope siteScope;

    @Inject
    SystemIdentity system;

    @Inject
    SiteContext context;

    @Inject
    DuePosts due;

    @Inject
    Posts posts;

    @Inject
    PostLocks locks;

    /**
     * Runs on the housekeeping interval, counted from application start: the first run is one interval after start,
     * not at start, so a node joining or restarting does not purge at once.
     */
    @Scheduled(
            identity = IDENTITY,
            every = "${quarkpress.scheduling.housekeeping-interval:24h}",
            delayed = "${quarkpress.scheduling.housekeeping-interval:24h}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = SchedulingDisabled.class)
    void scheduled() {
        run();
    }

    /** Runs one housekeeping over every site, on a thread of its own, and returns what it purged. */
    public Result run() {
        return Jobs.detached(this::runAll);
    }

    /** Purges the old trash of {@code site} on a thread of its own; the number of posts deleted. */
    public int purgeTrash(SiteId site) {
        return Jobs.detached(() -> trash(site));
    }

    /** Purges the stale auto-drafts of {@code site} on a thread of its own; the number of posts deleted. */
    public int purgeAutoDrafts(SiteId site) {
        return Jobs.detached(() -> autoDrafts(site));
    }

    private Result runAll() {
        List<SiteId> sites;
        try {
            sites = network.call(() -> system.call(due::allSites));
        } catch (Exception e) {
            LOG.warn("the housekeeping could not list the sites", e);
            status.failed(new Failure(Optional.empty(), Optional.empty(), message(e), clock.now()));
            status.housekeepingRan(clock.now());
            return new Result(0, 0);
        }
        int trash = 0;
        int autoDrafts = 0;
        for (SiteId site : sites) {
            trash += trash(site);
            autoDrafts += autoDrafts(site);
        }
        status.housekeepingRan(clock.now());
        return new Result(trash, autoDrafts);
    }

    private int trash(SiteId site) {
        long threshold = PurgeRules.trashThreshold(PostDates.now(clock), postsConfig.emptyTrashDays());
        int limit = Batch.size(config.batchSize());
        List<PostId> candidates = select(site, "trash", () -> {
            List<PostId> purgeable = new ArrayList<>();
            long after = 0;
            while (true) {
                List<DuePosts.Trashed> page = due.trashedAfter(site, after, limit);
                for (DuePosts.Trashed row : page) {
                    if (PurgeRules.trashPurgeable(row.meta(), threshold)) {
                        purgeable.add(row.id());
                    }
                }
                if (page.isEmpty() || page.size() < limit) {
                    return purgeable;
                }
                after = page.get(page.size() - 1).id().value();
            }
        });
        return deleteAll(
                site,
                candidates,
                false,
                row -> row.status.equals("trash") && PurgeRules.trashPurgeable(row.meta, threshold));
    }

    private int autoDrafts(SiteId site) {
        Instant before = PurgeRules.autoDraftThreshold(PostDates.now(clock));
        int limit = Batch.size(config.batchSize());
        List<PostId> candidates = select(site, "auto-draft", () -> {
            List<PostId> stale = new ArrayList<>();
            long after = 0;
            while (true) {
                List<PostId> page = due.autoDraftsModifiedBefore(site, before, after, limit);
                stale.addAll(page);
                if (page.isEmpty() || page.size() < limit) {
                    return stale;
                }
                after = page.get(page.size() - 1).value();
            }
        });
        return deleteAll(
                site,
                candidates,
                true,
                row -> row.status.equals("auto-draft") && row.modifiedAt != null && row.modifiedAt.isBefore(before));
    }

    /**
     * The candidates of one purge, read in one site transaction that ends before any delete: none when the site has
     * scheduling off; none, with the failure recorded, when the selection fails.
     */
    private List<PostId> select(SiteId site, String what, Selection selection) {
        try {
            return siteScope.call(
                    site,
                    () -> context.features().isEnabled(SchedulingFeature.KEY) ? selection.select() : List.<PostId>of());
        } catch (Exception e) {
            LOG.warnf(e, "the %s candidates of site %s could not be listed; the site is retried next time", what, site);
            status.failed(new Failure(Optional.of(site), Optional.empty(), message(e), clock.now()));
            return List.of();
        }
    }

    /**
     * Deletes the candidates one by one, each in a site transaction of its own; a candidate is read again there under
     * the site's post lock and skipped unless {@code still} holds for it, as WordPress's handler reads a post again
     * just before deleting it. The delete takes the same lock again in the same transaction, so the check and the
     * delete see one state: a restore or a save of the candidate waits for the transaction to end, or landed before
     * the lock and the candidate is skipped.
     */
    private int deleteAll(SiteId site, List<PostId> candidates, boolean force, Predicate<PostEntity> still) {
        int deleted = 0;
        for (PostId post : candidates) {
            if (delete(site, post, force, still)) {
                deleted++;
            }
        }
        return deleted;
    }

    private boolean delete(SiteId site, PostId post, boolean force, Predicate<PostEntity> still) {
        try {
            return siteScope
                    .call(
                            site,
                            () -> system.call(() -> {
                                locks.lock(site);
                                return due.post(site, post).filter(still).isPresent()
                                        ? posts.delete(post, force)
                                        : Optional.<PostSnapshot>empty();
                            }))
                    .isPresent();
        } catch (Exception e) {
            return failed(site, post, e);
        }
    }

    private boolean failed(SiteId site, PostId post, Exception e) {
        LOG.warnf(e, "post %s of site %s could not be purged; it is retried next time", post, site);
        status.failed(new Failure(Optional.of(site), Optional.of(post), message(e), clock.now()));
        return false;
    }

    private static String message(Exception e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getName() : message;
    }

    @FunctionalInterface
    private interface Selection {
        List<PostId> select() throws Exception;
    }
}
