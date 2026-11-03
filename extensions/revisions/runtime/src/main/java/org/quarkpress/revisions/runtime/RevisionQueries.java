package org.quarkpress.revisions.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.TypedQuery;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.core.PostId;
import org.quarkpress.core.RevisionId;
import org.quarkpress.core.SiteId;
import org.quarkpress.persistence.runtime.Dialects;
import org.quarkpress.persistence.runtime.TenantIds;
import org.quarkpress.posts.runtime.SearchRank;
import org.quarkpress.posts.runtime.SearchTerms;
import org.quarkpress.revisions.RevisionCount;
import org.quarkpress.revisions.RevisionOrder;
import org.quarkpress.revisions.RevisionQuery;

/**
 * The listing queries over revisions; every query names the site. The search matches and ranks as the posts module's
 * does ({@code SearchRank}); a search longer than the posts module's limit is ignored, as WordPress drops it. Every
 * order but {@code INCLUDE} is a database order; {@code INCLUDE} sorts the included rows in Java (at most as many as
 * the include list names), the limit and offset after that.
 */
@ApplicationScoped
public class RevisionQueries {

    @Inject
    RevisionRepository revisions;

    /** The post's revisions as {@code query} asks for them (the post's limit is the caller's concern). */
    public List<RevisionEntity> ofPost(SiteId site, PostId post, RevisionQuery query) {
        Where where = where(site, post, query);
        boolean inJava = query.order() == RevisionOrder.INCLUDE;
        String order = inJava ? "" : orderBy(query, where);
        TypedQuery<RevisionEntity> typed = where.bind(revisions
                .getEntityManager()
                .createQuery("select r from RevisionEntity r" + where.clause() + order, RevisionEntity.class));
        if (!inJava) {
            query.offset().ifPresent(typed::setFirstResult);
            query.limit().ifPresent(typed::setMaxResults);
            return typed.getResultList();
        }
        List<RevisionEntity> rows = new ArrayList<>(typed.getResultList());
        rows.sort(Comparator.comparingInt(row -> query.include().indexOf(RevisionId.of(row.id))));
        int from = Math.min(query.offset().orElse(0), rows.size());
        int to = query.limit().isPresent() ? Math.min(from + query.limit().getAsInt(), rows.size()) : rows.size();
        return rows.subList(from, to);
    }

    /** The number of rows {@link #ofPost} would list, the limit and offset aside. */
    public long count(SiteId site, PostId post, RevisionQuery query) {
        Where where = where(site, post, query);
        return where.bind(revisions
                        .getEntityManager()
                        .createQuery("select count(r) from RevisionEntity r" + where.clause(), Long.class))
                .getSingleResult();
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

    /** The number of the site's revisions, autosaves included. */
    public long countOnSite(SiteId site) {
        return revisions
                .getEntityManager()
                .createQuery("select count(r) from RevisionEntity r where r.siteId = :site", Long.class)
                .setParameter("site", TenantIds.param(site))
                .getSingleResult();
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

    private Where where(SiteId site, PostId post, RevisionQuery query) {
        Where where = new Where();
        where.and("r.siteId = " + where.param(TenantIds.param(site)));
        where.and("r.postId = " + where.param(post.value()));
        switch (query.autosaves()) {
            case ONLY -> where.and("r.autosave = true");
            case EXCLUDE -> where.and("r.autosave = false");
            case INCLUDE -> {}
        }
        query.author().ifPresent(author -> where.and("r.authorId = " + where.param(author.value())));
        if (!query.include().isEmpty()) {
            where.and("r.id in " + where.param(ids(query.include())));
        }
        if (!query.exclude().isEmpty()) {
            where.and("r.id not in " + where.param(ids(query.exclude())));
        }
        query.search().filter(RevisionQueries::searched).ifPresent(search -> {
            SearchTerms.Parsed parsed = SearchTerms.parse(search, false);
            where.parsed = Optional.of(parsed);
            for (String term : parsed.positive()) {
                String like = where.param(SearchRank.like(term));
                where.and(SearchRank.contains("r.title", like) + " or " + SearchRank.contains("r.excerpt", like)
                        + " or " + SearchRank.contains("r.content", like));
            }
            for (String term : parsed.excluded()) {
                String like = where.param(SearchRank.like(term));
                where.and(SearchRank.lacks("r.title", like) + " and " + SearchRank.lacks("r.excerpt", like) + " and "
                        + SearchRank.lacks("r.content", like));
            }
        });
        return where;
    }

    /** Whether WordPress searches for the string: not empty and within its byte limit. */
    private static boolean searched(String search) {
        return !search.isEmpty() && search.getBytes(StandardCharsets.UTF_8).length <= SearchTerms.MAX_BYTES;
    }

    private static List<Long> ids(List<RevisionId> ids) {
        return ids.stream().map(RevisionId::value).toList();
    }

    /**
     * The order clause; a relevance order whose search was dropped, or which WordPress does not rank, orders by
     * time, newest first.
     */
    private String orderBy(RevisionQuery query, Where where) {
        String direction = query.ascending() ? " asc" : " desc";
        return switch (query.order()) {
            case DATE -> " order by r.createdAt" + direction + ", r.id" + direction;
            case ID -> " order by r.id" + direction;
            case TITLE -> " order by " + title() + direction + ", r.id" + direction;
            // the names {post}-autosave-v1 and {post}-revision-v1: autosaves first ascending, ties by id ascending
            case SLUG -> " order by r.autosave" + (query.ascending() ? " desc" : " asc") + ", r.id asc";
            case INCLUDE_SLUGS -> " order by r.createdAt asc, r.id asc";
            case RELEVANCE ->
                " order by "
                        + where.parsed()
                                .flatMap(parsed -> SearchRank.expression(parsed, false, "r", where::param))
                                .map(rank -> rank + ", ")
                                .orElse("")
                        + "r.createdAt desc, r.id desc";
            case INCLUDE -> throw new IllegalArgumentException("ordered in Java: " + query.order());
        };
    }

    /**
     * The title ordered by code point on both databases, as the posts module orders titles: MySQL's column already
     * compares that way, and on PostgreSQL the expression is compared under {@code ucs_basic}.
     */
    private String title() {
        return Dialects.postgresql(revisions.getSession()) ? "collate(lower(r.title) as ucs_basic)" : "lower(r.title)";
    }

    /** The conditions of one query with their parameters, each condition in parentheses. */
    private static final class Where {

        private final StringBuilder clause = new StringBuilder();
        private final Map<String, Object> params = new LinkedHashMap<>();
        private Optional<SearchTerms.Parsed> parsed = Optional.empty();

        void and(String condition) {
            clause.append(clause.isEmpty() ? " where (" : " and (")
                    .append(condition)
                    .append(')');
        }

        /** A placeholder bound to {@code value}, for a condition or the order. */
        String param(Object value) {
            String name = "p" + params.size();
            params.put(name, value);
            return ":" + name;
        }

        String clause() {
            return clause.toString();
        }

        Optional<SearchTerms.Parsed> parsed() {
            return parsed;
        }

        <T> TypedQuery<T> bind(TypedQuery<T> query) {
            params.forEach(query::setParameter);
            return query;
        }
    }
}
