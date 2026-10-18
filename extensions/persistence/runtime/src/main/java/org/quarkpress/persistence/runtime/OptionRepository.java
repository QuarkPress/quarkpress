package org.quarkpress.persistence.runtime;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.quarkpress.core.SiteId;

/**
 * Site options. Every query names the site, on top of the site filter. Names must already be normalized: leading or
 * trailing whitespace is refused with {@link IllegalArgumentException}.
 */
@ApplicationScoped
public class OptionRepository implements PanacheRepositoryBase<OptionEntity, Long> {

    public Optional<OptionEntity> findOption(SiteId site, String name) {
        return find("siteId = ?1 and name = ?2", TenantIds.param(site), StoredOptionNames.require(name))
                .firstResultOptional();
    }

    /**
     * The option {@code name} if it exists, locked for update (PESSIMISTIC_WRITE) until the transaction ends, with its
     * state read under the lock. A missing option is not locked (on MySQL a locking read of a missing row takes a gap
     * lock that concurrent inserts deadlock on); insert it with {@link OptionInserts} instead.
     *
     * <p>The state is read by the locking query itself: the option found by the plain lookup is detached first, because
     * a refresh reads without the lock (on MySQL from the transaction's REPEATABLE READ snapshot) and a managed
     * instance would keep its snapshot state.
     *
     * <p>On MySQL, if the option was deleted by a concurrent transaction after the plain lookup, the locking query by
     * id reads a missing row and takes a gap lock; two transactions that then insert into that gap can deadlock, and
     * InnoDB rolls one of them back. This needs concurrent deletes of the same option and is rare.
     */
    public Optional<OptionEntity> lockOption(SiteId site, String name) {
        Optional<OptionEntity> found = findOption(site, name);
        if (found.isEmpty()) {
            return found;
        }
        Long id = found.get().id;
        getEntityManager().detach(found.get());
        return find("id = ?1 and siteId = ?2", id, TenantIds.param(site))
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .singleResultOptional();
    }

    /**
     * The option {@code name}, locked for update (PESSIMISTIC_WRITE) until the transaction ends, with its state read by
     * the locking query. Use it when the option is known to exist and this transaction has not read it without the
     * lock, for example right after an insert-if-absent lost to a concurrent insert (a plain lookup of it found
     * nothing, so it was not loaded); {@link #lockOption} handles an option that may have been read.
     */
    public Optional<OptionEntity> findOptionForUpdate(SiteId site, String name) {
        return find("siteId = ?1 and name = ?2", TenantIds.param(site), StoredOptionNames.require(name))
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .singleResultOptional();
    }

    public List<OptionEntity> autoloaded(SiteId site) {
        return list("siteId = ?1 and autoload = true", TenantIds.param(site));
    }
}
