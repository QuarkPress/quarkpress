package org.quarkpress.taxonomy.runtime;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TermId;
import org.quarkpress.persistence.runtime.TenantIds;

/** Terms. Every query names the site, on top of the site filter. */
@ApplicationScoped
public class TermRepository implements PanacheRepositoryBase<TermEntity, Long> {

    public Optional<TermEntity> findTerm(SiteId site, TermId id) {
        return find("siteId = ?1 and id = ?2", TenantIds.param(site), id.value())
                .firstResultOptional();
    }

    /** The term if it belongs to {@code taxonomy}; a term of another taxonomy is not found. */
    public Optional<TermEntity> findTerm(SiteId site, TermId id, String taxonomy) {
        return find("siteId = ?1 and id = ?2 and taxonomy = ?3", TenantIds.param(site), id.value(), taxonomy)
                .firstResultOptional();
    }

    /**
     * The term, locked for update until the transaction ends and read again under the lock, so its state is the
     * latest committed one. Pending changes of the caller's transaction are written first.
     */
    public Optional<TermEntity> lockTerm(SiteId site, TermId id) {
        flush();
        Optional<TermEntity> found = findTerm(site, id);
        found.ifPresent(row -> getEntityManager().refresh(row, LockModeType.PESSIMISTIC_WRITE));
        return found;
    }

    /** The terms among {@code ids}, locked in ascending id order (so two writers never wait for each other in turn). */
    public List<TermEntity> lockTerms(SiteId site, Collection<TermId> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        flush();
        List<TermEntity> rows = find(
                        "siteId = ?1 and id in ?2 order by id",
                        TenantIds.param(site),
                        ids.stream().map(TermId::value).toList())
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .list();
        // a row already in this transaction's context keeps its old state on a query: read it again
        rows.forEach(row -> getEntityManager().refresh(row, LockModeType.PESSIMISTIC_WRITE));
        return rows;
    }
}
