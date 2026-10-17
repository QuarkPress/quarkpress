package org.quarkpress.persistence.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;

/** Ties option writes to their transaction: observer failures roll it back, and a rollback undoes cache changes. */
@ApplicationScoped
public class OptionTransactions {

    @Inject
    TransactionSynchronizationRegistry registry;

    /**
     * Fires an event; an observer that throws marks the transaction for rollback before the exception propagates, so
     * the write it was told about is never committed, whatever the exception type.
     */
    public void fire(Runnable fire) {
        try {
            fire.run();
        } catch (RuntimeException failure) {
            try {
                registry.setRollbackOnly();
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    /** Runs {@code undo} after the current transaction completes without committing. */
    public void onRollback(Runnable undo) {
        registry.registerInterposedSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {}

            @Override
            public void afterCompletion(int status) {
                if (status != Status.STATUS_COMMITTED) {
                    undo.run();
                }
            }
        });
    }
}
