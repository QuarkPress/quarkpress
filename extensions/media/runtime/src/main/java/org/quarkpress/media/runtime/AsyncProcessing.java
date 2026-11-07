package org.quarkpress.media.runtime;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
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
 * attachment honours them; the run removes them. A failing run is logged; the attachment keeps whatever state its last
 * save gave it.
 */
@ApplicationScoped
public class AsyncProcessing {

    private static final Logger LOG = Logger.getLogger(AsyncProcessing.class);

    /** The options of the upload the current thread is storing. */
    private static final ThreadLocal<SizeGenerator.Options> STORING = new ThreadLocal<>();

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

    /** Runs {@code store}, keeping {@code options} with the attachment it stores. */
    MediaSnapshot store(SizeGenerator.Options options, Supplier<MediaSnapshot> store) {
        STORING.set(options);
        try {
            return store.get();
        } finally {
            STORING.remove();
        }
    }

    /** Inside the storing transaction: writes the upload's options other than the defaults into its row. */
    void remember(@Observes MediaStored event) {
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

    /** After the storing transaction committed: the run, in the attachment's site, as the system. */
    void process(@ObservesAsync MediaStored event) {
        if (config.processing() != MediaConfig.ProcessingMode.ASYNC) {
            return;
        }
        SiteId site = event.site();
        PostId post = event.media().post().id();
        try {
            siteScope.call(
                    site,
                    () -> system.call(() -> QuarkusTransaction.suspendingExisting()
                            .call(() -> generator.run(site, post, SizeGenerator.Options.DEFAULT))));
        } catch (Throwable failure) {
            LOG.warnf(failure, "the background processing of attachment %s of site %s failed", post.value(), site);
        }
    }
}
