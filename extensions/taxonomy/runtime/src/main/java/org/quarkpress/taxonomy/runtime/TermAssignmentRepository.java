package org.quarkpress.taxonomy.runtime;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TermId;
import org.quarkpress.persistence.runtime.TenantIds;

/** Assignments of terms to posts. Every query names the site, on top of the site filter. */
@ApplicationScoped
public class TermAssignmentRepository implements PanacheRepositoryBase<TermAssignmentEntity, Long> {

    /** The post's assignments in every taxonomy, by ascending term id. */
    public List<TermAssignmentEntity> ofPost(SiteId site, PostId post) {
        return list("siteId = ?1 and postId = ?2 order by termId", TenantIds.param(site), post.value());
    }

    /** The posts in a term, by ascending id. */
    public List<PostId> postsOf(SiteId site, TermId term) {
        return getEntityManager()
                .createQuery(
                        "select a.postId from TermAssignmentEntity a where a.siteId = :site and a.termId = :term"
                                + " order by a.postId",
                        Long.class)
                .setParameter("site", TenantIds.param(site))
                .setParameter("term", term.value())
                .getResultStream()
                .map(PostId::of)
                .toList();
    }

    /** The post's assignment to the term. */
    public Optional<TermAssignmentEntity> find(SiteId site, PostId post, TermId term) {
        return find("siteId = ?1 and postId = ?2 and termId = ?3", TenantIds.param(site), post.value(), term.value())
                .firstResultOptional();
    }
}
