package org.quarkpress.revisions.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.Optional;
import org.quarkpress.core.PostId;
import org.quarkpress.core.RevisionId;
import org.quarkpress.core.SiteId;
import org.quarkpress.persistence.runtime.TenantIds;
import org.quarkpress.revisions.RevisionCount;
import org.quarkpress.revisions.RevisionQuery;

/** The listing queries over revisions; every query names the site. */
@ApplicationScoped
public class RevisionQueries {

    @Inject
    RevisionRepository revisions;

    /** The post's revisions as {@code query} asks for them (the post's limit is the caller's concern). */
    public List<RevisionEntity> ofPost(SiteId site, PostId post, RevisionQuery query) {
        StringBuilder hql =
                new StringBuilder("select r from RevisionEntity r where r.siteId = :site and r.postId = :post");
        switch (query.autosaves()) {
            case ONLY -> hql.append(" and r.autosave = true");
            case EXCLUDE -> hql.append(" and r.autosave = false");
            case INCLUDE -> {}
        }
        if (query.author().isPresent()) {
            hql.append(" and r.authorId = :author");
        }
        hql.append(query.ascending() ? " order by r.createdAt asc, r.id asc" : " order by r.createdAt desc, r.id desc");
        TypedQuery<RevisionEntity> typed = revisions
                .getEntityManager()
                .createQuery(hql.toString(), RevisionEntity.class)
                .setParameter("site", TenantIds.param(site))
                .setParameter("post", post.value());
        query.author().ifPresent(author -> typed.setParameter("author", author.value()));
        query.offset().ifPresent(typed::setFirstResult);
        query.limit().ifPresent(typed::setMaxResults);
        return typed.getResultList();
    }

    /** The newest autosave of any user (by time, then by the highest id). */
    public Optional<RevisionEntity> latestAutosave(SiteId site, PostId post) {
        return ofPost(
                        site,
                        post,
                        RevisionQuery.builder()
                                .autosaves(RevisionQuery.Autosaves.ONLY)
                                .limit(1)
                                .build())
                .stream()
                .findFirst();
    }

    /** The newest revision of any kind and the number of all revisions. */
    public RevisionCount latestAndCount(SiteId site, PostId post) {
        long count = revisions.countOfPost(site, post);
        Optional<RevisionEntity> first = ofPost(
                        site, post, RevisionQuery.builder().limit(1).build())
                .stream()
                .findFirst();
        return new RevisionCount(first.map(row -> RevisionId.of(row.id)), count);
    }
}
