package org.quarkpress.posts.runtime;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.persistence.runtime.TenantIds;

/** Posts. Every query names the site, on top of the site filter. */
@ApplicationScoped
public class PostRepository implements PanacheRepositoryBase<PostEntity, Long> {

    public Optional<PostEntity> findPost(SiteId site, PostId id) {
        return find("siteId = ?1 and id = ?2", TenantIds.param(site), id.value())
                .firstResultOptional();
    }

    /**
     * The post, locked for update until the transaction ends and read again under the lock, so its state is the
     * latest committed one (on MySQL a plain read would see the transaction's older snapshot). Pending changes of
     * the caller's transaction are written first.
     */
    public Optional<PostEntity> lockPost(SiteId site, PostId id) {
        flush();
        Optional<PostEntity> found = findPost(site, id);
        found.ifPresent(row -> getEntityManager().refresh(row, LockModeType.PESSIMISTIC_WRITE));
        return found;
    }

    /** The ids of the site's sticky posts, ascending. */
    public List<PostId> stickyIds(SiteId site) {
        return getEntityManager()
                .createQuery(
                        "select p.id from PostEntity p where p.siteId = :site and p.sticky = true order by p.id",
                        Long.class)
                .setParameter("site", TenantIds.param(site))
                .getResultStream()
                .map(PostId::of)
                .toList();
    }
}
