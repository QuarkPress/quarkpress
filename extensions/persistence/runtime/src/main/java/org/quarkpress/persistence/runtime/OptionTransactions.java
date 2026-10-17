package org.quarkpress.persistence.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;

/**
 * Ties option writes to their transaction: observer failures roll it back, and the request's caches are dropped again
 * when it completes.
 */
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

    /**
     * Runs {@code forget} after the current transaction completes, whether it commits or rolls back: a thread sharing
     * the request may have cached the old value while the transaction was open, and after a rollback the value the
     * writer cached is gone.
     */
    public void afterCompletion(Runnable forget) {
        registry.registerInterposedSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {}

            @Override
            public void afterCompletion(int status) {
                forget.run();
            }
        });
    }
}
