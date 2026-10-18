package org.quarkpress.persistence.runtime;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import java.util.Optional;

@ApplicationScoped
public class NetworkOptionRepository implements PanacheRepositoryBase<NetworkOptionEntity, String> {

    /** The network option {@code name}; names must already be normalized (see {@link OptionRepository}). */
    public Optional<NetworkOptionEntity> findOption(String name) {
        return findByIdOptional(StoredOptionNames.require(name));
    }

    /**
     * The network option {@code name}, locked for update (PESSIMISTIC_WRITE) until the transaction ends, with its state
     * read by the locking query. An instance this transaction already read without the lock is detached first: a
     * refresh would read without the lock (on MySQL from the transaction's REPEATABLE READ snapshot) and a managed
     * instance would keep its stale state. This transaction's pending changes are flushed first, so that the detach
     * does not discard them. The plain lookup costs one select when the option is not managed yet. Callers take {@link
     * NetworkLockRepository#lock} first, which serializes the writers, so the locking read of a missing option takes no
     * gap lock another writer could wait on.
     */
    public Optional<NetworkOptionEntity> findOptionForUpdate(String name) {
        String key = StoredOptionNames.require(name);
        // Pending changes of this transaction are written first: evicting an instance would drop its queued update.
        getEntityManager().flush();
        findByIdOptional(key).ifPresent(getEntityManager()::detach);
        return findByIdOptional(key, LockModeType.PESSIMISTIC_WRITE);
    }
}
