package org.quarkpress.media.runtime;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.persistence.runtime.TenantIds;

/** Queries over the media rows of attachments; every query names the site. */
@ApplicationScoped
public class MediaRepository implements PanacheRepositoryBase<MediaEntity, Long> {

    public Optional<MediaEntity> findMedia(SiteId site, PostId post) {
        return find("siteId = ?1 and postId = ?2", TenantIds.param(site), post.value())
                .firstResultOptional();
    }

    /** The row locked for update and read again under the lock; pending changes are written first. */
    public Optional<MediaEntity> lockMedia(SiteId site, PostId post) {
        flush();
        Optional<MediaEntity> found = findMedia(site, post);
        found.ifPresent(row -> getEntityManager().refresh(row, LockModeType.PESSIMISTIC_WRITE));
        return found;
    }

    /** The bytes of every file of the site's attachments; 0 without any. */
    public long storedBytes(SiteId site) {
        Long sum = getEntityManager()
                .createQuery("select sum(m.storedBytes) from MediaEntity m where m.siteId = ?1", Long.class)
                .setParameter(1, TenantIds.param(site))
                .getSingleResult();
        return sum == null ? 0 : sum;
    }
}
