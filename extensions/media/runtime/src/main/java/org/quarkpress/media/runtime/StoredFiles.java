package org.quarkpress.media.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.jboss.logging.Logger;

/** Ties stored files to the transaction that refers to them. */
@ApplicationScoped
public class StoredFiles {

    private static final Logger LOG = Logger.getLogger(StoredFiles.class);

    @Inject
    TransactionSynchronizationRegistry registry;

    @Inject
    ActiveStorage storage;

    /**
     * Removes the file under {@code key} once the current transaction has rolled back, so a file stored for a row
     * that was never written does not stay behind. A failure to remove it is logged. When the outcome is unknown (the
     * database may have committed the row that names the file), the file is kept and a warning logged: a file no row
     * names is better than a row naming a file that is gone.
     */
    public void removeOnRollback(String key) {
        registry.registerInterposedSynchronization(new RemoveOnRollback(storage, key));
    }

    /** The synchronisation of one stored file. */
    record RemoveOnRollback(ActiveStorage storage, String key) implements Synchronization {

        @Override
        public void beforeCompletion() {}

        @Override
        public void afterCompletion(int status) {
            if (status == Status.STATUS_COMMITTED) {
                return;
            }
            if (status != Status.STATUS_ROLLEDBACK) {
                LOG.warnf(
                        "the outcome of the transaction that stored %s is unknown (status %d); the file is kept",
                        key, status);
                return;
            }
            try {
                storage.storage().delete(key);
            } catch (RuntimeException failure) {
                LOG.warnf(failure, "the stored file %s of a rolled-back upload could not be removed", key);
            }
        }
    }
}
