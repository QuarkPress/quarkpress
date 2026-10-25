package org.quarkpress.scheduling.runtime;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;
import org.quarkpress.core.PostId;
import org.quarkpress.core.QuarkPressClock;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.persistence.NetworkScope;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.Posts;
import org.quarkpress.posts.runtime.PostDates;
import org.quarkpress.scheduling.SchedulingFeature;
import org.quarkpress.scheduling.SchedulingStatus.Failure;
import org.quarkpress.scheduling.SchedulingStatus.Published;
import org.quarkpress.security.SystemIdentity;

/**
 * The polling job that publishes scheduled posts whose time has come, as WordPress's cron does when a visit runs
 * its scheduled-publishing events. Each poll lists the sites with due posts in a network scope of its own, then
 * visits each site in a scope of that site: a site with scheduling off is skipped, else up to the batch size of its
 * due posts are published one by one, each in a site scope and transaction of its own, as the system identity. A
 * post whose publication fails is logged and recorded, and stays scheduled for the next poll; the poll goes on
 * with the next post.
 */
@ApplicationScoped
public class PublishPoll {

    public static final String IDENTITY = "quarkpress-scheduling-publish";

    private static final Logger LOG = Logger.getLogger(PublishPoll.class);

    @Inject
    SchedulingConfig config;

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

    @Scheduled(
            identity = IDENTITY,
            every = "${quarkpress.scheduling.interval:15s}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = SchedulingDisabled.class)
    void scheduled() {
        poll();
    }

    /** Runs one poll on a thread of its own and returns the posts it published, in order. */
    public List<Published> poll() {
        return Jobs.detached(this::run);
    }

    private List<Published> run() {
        status.pollStarted(clock.now());
        Instant now = PostDates.now(clock);
        List<SiteId> sites;
        try {
            sites = network.call(() -> system.call(() -> due.sitesWithDuePosts(now)));
        } catch (Exception e) {
            LOG.warn("the scheduled-publishing poll could not list the sites with due posts", e);
            status.failed(new Failure(Optional.empty(), Optional.empty(), message(e), clock.now()));
            status.pollEnded(clock.now(), List.of());
            return List.of();
        }
        List<Published> published = new ArrayList<>();
        for (SiteId site : sites) {
            List<PostId> duePosts;
            try {
                duePosts = siteScope.call(
                        site,
                        () -> context.features().isEnabled(SchedulingFeature.KEY)
                                ? due.duePosts(site, now, Batch.size(config.batchSize()))
                                : List.<PostId>of());
            } catch (Exception e) {
                LOG.warnf(
                        e, "the due posts of site %s could not be listed; the site is retried on the next poll", site);
                status.failed(new Failure(Optional.of(site), Optional.empty(), message(e), clock.now()));
                continue;
            }
            for (PostId post : duePosts) {
                publish(site, post, published);
            }
        }
        status.pollEnded(clock.now(), published);
        return published;
    }

    private void publish(SiteId site, PostId post, List<Published> published) {
        try {
            boolean done = siteScope.call(site, () -> system.call(() -> posts.publishIfDue(post)));
            if (done) {
                published.add(new Published(site, post));
            }
        } catch (PostException e) {
            if (e.code().equals("invalid_post")) {
                LOG.debugf("post %s of site %s was gone before it could be published", post, site);
                return;
            }
            failed(site, post, e);
        } catch (Exception e) {
            failed(site, post, e);
        }
    }

    private void failed(SiteId site, PostId post, Exception e) {
        LOG.warnf(
                e,
                "post %s of site %s could not be published; it stays scheduled and is retried on the next poll",
                post,
                site);
        status.failed(new Failure(Optional.of(site), Optional.of(post), message(e), clock.now()));
    }

    private static String message(Exception e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getName() : message;
    }
}
