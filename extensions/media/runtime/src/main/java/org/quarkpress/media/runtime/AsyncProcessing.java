package org.quarkpress.media.runtime;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.jboss.logging.Logger;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.MediaStored;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.security.SystemIdentity;

/**
 * Keeps an upload's options with its attachment and, in the {@code async} processing mode, processes each stored
 * upload once its storing transaction has committed, on a thread of its own bound to the attachment's site, as the
 * system. The options of an upload (no sizes, no format conversion) are written into the pending row's stored
 * metadata while it is stored, so that the background run, a start-up resume or a post-process of the still-pending
 * attachment honours them; the run removes them. At most the configured number of background runs (by default the
 * number of concurrent decodes) hold a temporary copy of their file at once; the others wait for their turn first,
 * without a transaction. A failing run is logged; the attachment keeps whatever state its last save gave it, and is
 * never left pending. An upload stored {@linkplain #storeDeferred deferred} is not run once its transaction commits:
 * its run is {@linkplain #queue queued} by the caller later, or never.
 */
@ApplicationScoped
public class AsyncProcessing {

    private static final Logger LOG = Logger.getLogger(AsyncProcessing.class);

    /** The options of the upload the current thread is storing. */
    private static final ThreadLocal<SizeGenerator.Options> STORING = new ThreadLocal<>();

    /** Whether the upload the current thread is storing is processed only when its caller queues it. */
    private static final ThreadLocal<Boolean> DEFERRING = new ThreadLocal<>();

    /** A background run asked for by {@link #queue}. */
    record Queued(SiteId site, PostId post) {}

    /** The attachments stored deferred whose committed storing has not yet reached {@link #process}. */
    private final Set<Long> deferred = ConcurrentHashMap.newKeySet();

    @Inject
    MediaConfig config;

    @Inject
    SiteScope siteScope;

    @Inject
    SystemIdentity system;

    @Inject
    SizeGenerator generator;

    @Inject
    MediaRepository mediaRows;

    @Inject
    TransactionManager transactionManager;

    @Inject
    TransactionSynchronizationRegistry registry;

    @Inject
    Event<Queued> queued;

    /** The background runs that may hold a temporary copy of their file at once, handed out in arrival order. */
    private Semaphore runs;

    @PostConstruct
    void create() {
        int limit = Math.max(1, config.asyncRuns().orElseGet(() -> DecodeLimiter.limit(config)));
        LOG.debugf("at most %d background runs hold a copy of their file at once", limit);
        runs = new Semaphore(limit, true);
    }

    /** Runs {@code store}, keeping {@code options} with the attachment it stores. */
    MediaSnapshot store(SizeGenerator.Options options, Supplier<MediaSnapshot> store) {
        STORING.set(options);
        try {
            return store.get();
        } finally {
            STORING.remove();
        }
    }

    /**
     * As {@link #store}; in the {@code async} mode the stored attachment is not processed once its transaction
     * commits, but only when {@link #queue} asks for it.
     */
    MediaSnapshot storeDeferred(SizeGenerator.Options options, Supplier<MediaSnapshot> store) {
        DEFERRING.set(Boolean.TRUE);
        try {
            return store(options, store);
        } finally {
            DEFERRING.remove();
        }
    }

    /** Queues the background run of an attachment stored deferred, on another thread. */
    void queue(SiteId site, PostId post) {
        queued.fireAsync(new Queued(site, post)).exceptionally(failure -> {
            LOG.warnf(failure, "the background processing of attachment %s of site %s failed", post.value(), site);
            return null;
        });
    }

    /** Inside the storing transaction: writes the upload's options other than the defaults into its row. */
    void remember(@Observes MediaStored event) {
        if (DEFERRING.get() != null) {
            defer(event.media().post().id().value());
        }
        SizeGenerator.Options options = STORING.get();
        if (options == null || options.equals(SizeGenerator.Options.DEFAULT)) {
            return;
        }
        mediaRows.findMedia(event.site(), event.media().post().id()).ifPresent(row -> {
            ObjectNode extra =
                    row.extra instanceof ObjectNode stored ? stored.deepCopy() : JsonNodeFactory.instance.objectNode();
            extra.set(SizeGenerator.UPLOAD_OPTIONS, options.toJson());
            row.extra = extra;
        });
    }

    /**
     * Marks the attachment being stored as deferred until its committed storing reaches {@link #process}; a storing
     * that does not commit takes the mark away again.
     */
    private void defer(long post) {
        deferred.add(post);
        registry.registerInterposedSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {}

            @Override
            public void afterCompletion(int status) {
                if (status != Status.STATUS_COMMITTED) {
                    deferred.remove(post);
                }
            }
        });
    }

    /** After the storing transaction committed: the run, in the attachment's site, as the system. */
    void process(@ObservesAsync MediaStored event) {
        // a deferred attachment is run when its caller queues it, if ever
        if (deferred.remove(event.media().post().id().value())
                || config.processing() != MediaConfig.ProcessingMode.ASYNC) {
            return;
        }
        runLogged(event.site(), event.media().post().id());
    }

    /** A queued run, on a thread of its own. */
    void processQueued(@ObservesAsync Queued event) {
        runLogged(event.site(), event.post());
    }

    private void runLogged(SiteId site, PostId post) {
        try {
            run(site, post);
        } catch (Throwable failure) {
            LOG.warnf(failure, "the background processing of attachment %s of site %s failed", post.value(), site);
        }
    }

    /**
     * Processes the attachment {@code post} of {@code site} as the system, bound to that site, from a thread without a
     * transaction. The site's scope opens a transaction of its own; it is ended before the run and a new one is begun
     * after it, so that no transaction stays open, and so none can time out, however long the run takes. When the run
     * throws, the attachment is marked failed if it is still pending, and the failure is rethrown. The run first waits
     * for its turn among the background runs, before it opens a transaction or copies the file, and gives the turn
     * back however it ends.
     */
    Optional<MediaSnapshot> run(SiteId site, PostId post) throws Exception {
        if (transactionManager.getStatus() != Status.STATUS_NO_TRANSACTION) {
            throw new IllegalStateException(
                    "a background run of attachment " + post.value() + " must start outside a transaction");
        }
        runs.acquireUninterruptibly();
        try {
            return runInTurn(site, post);
        } finally {
            runs.release();
        }
    }

    private Optional<MediaSnapshot> runInTurn(SiteId site, PostId post) throws Exception {
        try {
            return siteScope.call(site, () -> {
                // the scope's own transaction, which has written nothing
                QuarkusTransaction.commit();
                Optional<MediaSnapshot> result;
                try {
                    result = system.call(() -> generator.run(site, post, SizeGenerator.Options.DEFAULT));
                } catch (Throwable thrown) {
                    // the scope ends the transaction it began: it finds a new one, begun now
                    try {
                        QuarkusTransaction.begin();
                    } catch (Throwable beginFailure) {
                        thrown.addSuppressed(beginFailure);
                    }
                    throw thrown;
                }
                QuarkusTransaction.begin();
                return result;
            });
        } catch (Throwable failure) {
            try {
                siteScope.run(site, () -> generator.markFailedIfPending(site, post, failure));
            } catch (Throwable marking) {
                failure.addSuppressed(marking);
            }
            throw failure;
        }
    }
}
