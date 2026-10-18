package org.quarkpress.persistence.runtime;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionSynchronizationRegistry;

@ApplicationScoped
public class NetworkLockRepository implements PanacheRepositoryBase<NetworkLockEntity, Short> {

    /** The refusal of a network write that would wait for a lock its caller holds. */
    public static final String HELD_MESSAGE = "a network write cannot start inside a transaction that already holds"
            + " the network lock; call it outside that transaction";

    private static final String HELD = "org.quarkpress.persistence.runtime.NetworkLockRepository.held";

    @Inject
    TransactionSynchronizationRegistry registry;

    /**
     * Whether the current transaction has taken the network lock, or this is a scope thread (at any depth) spawned by
     * one that did. Only a transaction that is active or marked for rollback counts: a completing one has released
     * its lock. A network write that runs on a thread and in a transaction of its own would wait for that lock while
     * the holder waits for it, which no database can see.
     */
    public boolean heldByCurrentTransaction() {
        if (Scopes.spawnerHoldsNetworkLock()) {
            return true;
        }
        int status = registry.getTransactionStatus();
        if (status != Status.STATUS_ACTIVE && status != Status.STATUS_MARKED_ROLLBACK) {
            return false;
        }
        try {
            return registry.getResource(HELD) != null;
        } catch (IllegalStateException unavailable) {
            return false;
        }
    }

    /**
     * Locks the network lock row until the transaction ends. Every write of network data (sites, domain aliases,
     * network options) takes it first, so those writes run one after the other without locking rows that inserts of a
     * site's rows hold shared locks on (no foreign key refers to this row). Call it as the transaction's first
     * statement, so that later reads in the transaction see every write that committed before the lock was granted.
     */
    public void lock() {
        if (Scopes.spawnerHoldsNetworkLock()) {
            throw new IllegalStateException(HELD_MESSAGE);
        }
        if (findByIdOptional(NetworkLockEntity.ROW, LockModeType.PESSIMISTIC_WRITE)
                .isEmpty()) {
            throw new IllegalStateException("the qp_network_lock row is missing; QuarkPress's migrations create it");
        }
        registry.putResource(HELD, Boolean.TRUE);
    }
}
