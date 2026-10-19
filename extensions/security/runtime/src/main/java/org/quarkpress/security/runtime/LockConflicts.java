package org.quarkpress.security.runtime;

import jakarta.persistence.PessimisticLockException;
import java.sql.SQLException;

/**
 * Deadlocks and lock timeouts, on PostgreSQL or MySQL, however they are wrapped: failures of a transaction that met a
 * concurrent one, which the database rolled back (or will), and which a retry of the whole transaction may cure.
 */
final class LockConflicts {

    private LockConflicts() {}

    static boolean isDeadlockOrLockTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                int code = sql.getErrorCode();
                if ("40P01".equals(state) || "55P03".equals(state) || code == 1213 || code == 1205) {
                    return true;
                }
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    /**
     * {@code failure} as a {@link PessimisticLockException} (JPA's "retry the transaction") naming {@code what}, with
     * the original as its cause, when it is a deadlock or a lock timeout; otherwise {@code failure} unchanged.
     */
    static RuntimeException retryable(RuntimeException failure, String what) {
        if (failure instanceof PessimisticLockException || !isDeadlockOrLockTimeout(failure)) {
            return failure;
        }
        return new PessimisticLockException(
                what + " met a concurrent change (a deadlock or a lock timeout) and its transaction must be retried",
                failure);
    }
}
