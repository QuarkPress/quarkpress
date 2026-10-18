package org.quarkpress.persistence.runtime;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;

@ApplicationScoped
public class NetworkLockRepository implements PanacheRepositoryBase<NetworkLockEntity, Short> {

    /**
     * Locks the network lock row until the transaction ends. Every write of network data (sites, domain aliases,
     * network options) takes it first, so those writes run one after the other without locking rows that inserts of a
     * site's rows hold shared locks on (no foreign key refers to this row). Call it as the transaction's first
     * statement, so that later reads in the transaction see every write that committed before the lock was granted.
     */
    public void lock() {
        if (findByIdOptional(NetworkLockEntity.ROW, LockModeType.PESSIMISTIC_WRITE)
                .isEmpty()) {
            throw new IllegalStateException("the qp_network_lock row is missing; QuarkPress's migrations create it");
        }
    }
}
