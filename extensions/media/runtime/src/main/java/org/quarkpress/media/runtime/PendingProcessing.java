package org.quarkpress.media.runtime;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;
import org.quarkpress.core.PostId;
import org.quarkpress.core.QuarkPressClock;
import org.quarkpress.core.SiteId;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Processing;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.persistence.SiteScopedNativeSql;
import org.quarkpress.security.SystemIdentity;

/**
 * Processes again, at start-up in the {@code async} mode, the uploads an earlier run of the application stored but
 * never processed (it stopped before their background run). Only attachments whose post is older than five minutes
 * are taken, so that uploads another instance is processing right now are left alone, and at most a hundred per start.
 * The work runs on a thread of its own after start-up; failures are logged and never fail the start.
 */
@ApplicationScoped
@SiteScopedNativeSql
public class PendingProcessing {

    private static final Logger LOG = Logger.getLogger(PendingProcessing.class);

    /** How old an attachment left pending must be to be taken up again. */
    static final Duration MIN_AGE = Duration.ofMinutes(5);

    /** The most attachments taken up per start. */
    static final int LIMIT = 100;

    // every site's rows: the query names no site on purpose, and the run of each row is bound to its own site
    private static final String PENDING = "select m.site_id, m.post_id from qp_media m join qp_post p"
            + " on p.site_id = m.site_id and p.id = m.post_id where m.processing = 'pending' and p.created_at < ?1"
            + " order by p.created_at, m.post_id";

    @Inject
    MediaConfig config;

    @Inject
    MediaRepository mediaRows;

    @Inject
    SiteScope siteScope;

    @Inject
    SystemIdentity system;

    @Inject
    SizeGenerator generator;

    @Inject
    QuarkPressClock clock;

    void onStart(@Observes StartupEvent event) {
        if (config.processing() != MediaConfig.ProcessingMode.ASYNC) {
            return;
        }
        Thread.ofVirtual().name("quarkpress-media-resume").start(() -> {
            try {
                int resumed = resume(clock.now());
                if (resumed > 0) {
                    LOG.infof("%d uploads left unprocessed by an earlier run were processed", resumed);
                }
            } catch (Throwable failure) {
                LOG.warn("the uploads left unprocessed by an earlier run could not be processed", failure);
            }
        });
    }

    /**
     * Processes the attachments of every site still pending whose post was created more than five minutes before
     * {@code now}, oldest first, at most a hundred, each in its site as the system. Answers how many were processed
     * (left pending done or partial); one whose file is gone ends failed, and one whose run throws, whatever it
     * throws, is logged and left as it is: neither counts, and neither stops the others.
     */
    public int resume(Instant now) {
        int resumed = 0;
        for (Object[] row : pending(now.minus(MIN_AGE))) {
            SiteId site = SiteId.of(((Number) row[0]).longValue());
            PostId post = PostId.of(((Number) row[1]).longValue());
            try {
                Optional<MediaSnapshot> result = siteScope.call(
                        site,
                        () -> system.call(() -> QuarkusTransaction.suspendingExisting()
                                .call(() -> generator.run(site, post, SizeGenerator.Options.DEFAULT))));
                if (result.map(media ->
                                media.processing() == Processing.DONE || media.processing() == Processing.PARTIAL)
                        .orElse(false)) {
                    resumed++;
                }
            } catch (Throwable failure) {
                LOG.warnf(failure, "attachment %s of site %s left pending could not be processed", post.value(), site);
            }
        }
        return resumed;
    }

    private List<Object[]> pending(Instant before) {
        ManagedContext requestContext = Arc.container().requestContext();
        boolean activated = !requestContext.isActive();
        if (activated) {
            requestContext.activate();
        }
        try {
            return QuarkusTransaction.requiringNew()
                    .call(() -> mediaRows
                            .getSession()
                            .createNativeQuery(PENDING, Object[].class)
                            .setParameter(1, before)
                            .setMaxResults(LIMIT)
                            .getResultList());
        } finally {
            if (activated) {
                requestContext.terminate();
            }
        }
    }
}
