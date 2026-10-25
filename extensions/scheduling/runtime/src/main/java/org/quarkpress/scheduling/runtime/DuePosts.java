package org.quarkpress.scheduling.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.persistence.runtime.TenantIds;
import org.quarkpress.posts.runtime.PostEntity;
import org.quarkpress.posts.runtime.PostRepository;

/**
 * The reads of the scheduling jobs: which sites have due posts, which posts of a site are due, which sites exist,
 * and the pages of a site's trashed posts and stale auto-drafts the housekeeping purges.
 */
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

    /** A trashed post as the trash purge selects it: its id and its meta, where the trash time is kept. */
    public record Trashed(PostId id, JsonNode meta) {}

    /**
     * At most {@code limit} trashed posts of {@code site}, of any type, with an id above {@code afterId}, in id
     * order: one page of the trash, as ids and metas only, so a large trash is not loaded whole.
     */
    public List<Trashed> trashedAfter(SiteId site, long afterId, int limit) {
        return posts.getEntityManager()
                .createQuery(
                        "select p.id, p.meta from PostEntity p where p.siteId = :site and p.status = 'trash'"
                                + " and p.id > :after order by p.id",
                        Object[].class)
                .setParameter("site", TenantIds.param(site))
                .setParameter("after", afterId)
                .setMaxResults(limit)
                .getResultStream()
                .map(row -> new Trashed(PostId.of((Long) row[0]), (JsonNode) row[1]))
                .toList();
    }

    /** The post of {@code site} with {@code id}, as stored now; empty when it is gone. */
    public Optional<PostEntity> post(SiteId site, PostId id) {
        return posts.findPost(site, id);
    }

    /**
     * At most {@code limit} auto-drafts of {@code site}, of any type, last modified before {@code before}, with an
     * id above {@code afterId}, in id order: one page of the stale auto-drafts.
     */
    public List<PostId> autoDraftsModifiedBefore(SiteId site, Instant before, long afterId, int limit) {
        return posts.getEntityManager()
                .createQuery(
                        "select p.id from PostEntity p where p.siteId = :site and p.status = 'auto-draft'"
                                + " and p.modifiedAt < :before and p.id > :after order by p.id",
                        Long.class)
                .setParameter("site", TenantIds.param(site))
                .setParameter("before", before)
                .setParameter("after", afterId)
                .setMaxResults(limit)
                .getResultStream()
                .map(PostId::of)
                .toList();
    }
}
