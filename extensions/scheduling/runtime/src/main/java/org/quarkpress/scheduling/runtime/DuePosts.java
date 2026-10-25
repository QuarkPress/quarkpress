package org.quarkpress.scheduling.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.persistence.runtime.TenantIds;
import org.quarkpress.posts.runtime.PostRepository;

/** The reads of the scheduling jobs: which sites have due posts, which posts of a site are due, which sites exist. */
@ApplicationScoped
public class DuePosts {

    @Inject
    PostRepository posts;

    /**
     * The sites with at least one scheduled post due at {@code now} or earlier, in id order. Run it inside the
     * network scope, where the site filter sees every site: it names no site on purpose.
     */
    public List<SiteId> sitesWithDuePosts(Instant now) {
        return posts.getEntityManager()
                .createQuery(
                        "select distinct p.siteId from PostEntity p where p.status = 'future' and p.publishedAt <= :now"
                                + " order by p.siteId",
                        Long.class)
                .setParameter("now", now)
                .getResultStream()
                .map(SiteId::of)
                .toList();
    }

    /**
     * At most {@code limit} due scheduled posts of {@code site}, the earliest first and in id order within a second.
     */
    public List<PostId> duePosts(SiteId site, Instant now, int limit) {
        return posts.getEntityManager()
                .createQuery(
                        "select p.id from PostEntity p where p.siteId = :site and p.status = 'future'"
                                + " and p.publishedAt <= :now order by p.publishedAt, p.id",
                        Long.class)
                .setParameter("site", TenantIds.param(site))
                .setParameter("now", now)
                .setMaxResults(limit)
                .getResultStream()
                .map(PostId::of)
                .toList();
    }

    /** Every site of the network, in id order. */
    public List<SiteId> allSites() {
        return posts.getEntityManager()
                .createQuery("select s.id from SiteEntity s order by s.id", Long.class)
                .getResultStream()
                .map(SiteId::of)
                .toList();
    }
}
