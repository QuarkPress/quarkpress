package org.quarkpress.taxonomy.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.persistence.runtime.TenantIds;
import org.quarkpress.posts.TaxClause;
import org.quarkpress.posts.TermCondition;
import org.quarkpress.posts.TermConditionProvider;
import org.quarkpress.taxonomy.Taxonomies;
import org.quarkpress.taxonomy.TermException;

/**
 * The posts module's term filters answered over this site's terms, as WordPress's tax query decides them. Term ids are
 * made positive, zeros dropped and duplicates removed, then resolved within the taxonomy on the site; with
 * {@code includeChildren} on a hierarchical taxonomy each term stands for itself and all its descendants, read level
 * by level (one query per level). An {@code IN} clause matches posts with any resolved term and nothing when none
 * resolved; {@code AND} matches posts with every resolved term and nothing as soon as one listed or expanded id is
 * unknown; {@code NOT IN} matches posts with none and applies no condition when none resolved. Slugs are an
 * {@code IN} by slug; a not-exists clause matches posts with no term of the taxonomy. Every id list is bound in chunks
 * of at most {@value #CHUNK} ids, each its own parameter; since every id still binds one parameter and the databases
 * refuse a statement with too many, an expansion beyond {@value #MAX_EXPANDED_IDS} ids in one clause is refused with
 * {@code term_filter_too_large} instead of failing in the database.
 */
@ApplicationScoped
public class JpaTermConditions implements TermConditionProvider {

    /** The most ids one {@code in} list binds. */
    static final int CHUNK = 1000;

    /** The most ids one clause may hold after its {@code includeChildren} expansion. */
    public static final int MAX_EXPANDED_IDS = 10_000;

    private volatile int maxExpandedIds = MAX_EXPANDED_IDS;

    @Inject
    Taxonomies taxonomies;

    @Inject
    TermRepository terms;

    /** Lowers (or restores) the expansion cap; for tests of the refusal only. */
    public void limitExpandedIds(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("the expansion cap must be positive");
        }
        maxExpandedIds = limit;
    }

    @Override
    public TermCondition condition(SiteId site, TaxClause clause, String postId, String paramPrefix) {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(clause, "clause");
        Objects.requireNonNull(postId, "postId");
        Objects.requireNonNull(paramPrefix, "paramPrefix");
        return switch (clause) {
            case TaxClause.Terms byId -> terms(site, byId, postId, paramPrefix);
            case TaxClause.Slugs bySlug -> slugs(site, bySlug, postId, paramPrefix);
            case TaxClause.NotExists none -> notExists(site, none, postId, paramPrefix);
            case TaxClause.Group group -> throw new IllegalArgumentException("a term filter group is not a clause");
        };
    }

    private TermCondition terms(SiteId site, TaxClause.Terms clause, String postId, String prefix) {
        Set<Long> listed = new LinkedHashSet<>();
        for (Long id : clause.termIds()) {
            long positive = Math.abs(id);
            if (positive != 0) {
                listed.add(positive);
            }
        }
        Optional<TaxonomyDefinition> taxonomy = taxonomies.find(clause.taxonomy());
        List<Long> resolved =
                taxonomy.isEmpty() ? List.of() : resolve(site, taxonomy.get().key(), listed);
        if (clause.operator() == TaxClause.Operator.AND && resolved.size() != listed.size()) {
            return new TermCondition.Nothing();
        }
        if (clause.includeChildren() && taxonomy.isPresent() && taxonomy.get().hierarchical()) {
            resolved = withDescendants(site, taxonomy.get().key(), resolved);
        }
        return matching(site, resolved, clause.operator(), postId, prefix);
    }

    private TermCondition slugs(SiteId site, TaxClause.Slugs clause, String postId, String prefix) {
        Set<String> slugs = new LinkedHashSet<>(clause.slugs());
        Optional<TaxonomyDefinition> taxonomy = taxonomies.find(clause.taxonomy());
        List<Long> resolved = new ArrayList<>();
        if (taxonomy.isPresent() && !slugs.isEmpty()) {
            resolved = terms.getEntityManager()
                    .createQuery(
                            "select t.id from TermEntity t where t.siteId = :site and t.taxonomy = :taxonomy"
                                    + " and t.slug in :slugs order by t.id",
                            Long.class)
                    .setParameter("site", TenantIds.param(site))
                    .setParameter("taxonomy", taxonomy.get().key())
                    .setParameter("slugs", List.copyOf(slugs))
                    .getResultList();
        }
        return matching(site, resolved, TaxClause.Operator.IN, postId, prefix);
    }

    private static TermCondition notExists(SiteId site, TaxClause.NotExists clause, String postId, String prefix) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(prefix + "site", TenantIds.param(site));
        params.put(prefix + "taxonomy", clause.taxonomy());
        return new TermCondition.Jpql(
                postId + " not in (select a.postId from TermAssignmentEntity a, TermEntity t where a.siteId = :"
                        + prefix + "site and t.siteId = :" + prefix + "site and t.id = a.termId and t.taxonomy = :"
                        + prefix + "taxonomy)",
                params);
    }

    /**
     * The condition over the resolved term ids: no term matches nothing ({@code IN}, {@code AND}) or applies no
     * condition ({@code NOT IN}); otherwise the posts assigned any of the terms, none of them, or all of them (counted
     * per post).
     */
    private static TermCondition matching(
            SiteId site, List<Long> resolved, TaxClause.Operator operator, String postId, String prefix) {
        if (resolved.isEmpty()) {
            return operator == TaxClause.Operator.NOT_IN ? new TermCondition.None() : new TermCondition.Nothing();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(prefix + "site", TenantIds.param(site));
        String inLists = chunked("a.termId", resolved, prefix + "ids", params);
        String text = switch (operator) {
            case IN ->
                postId + " in (select a.postId from TermAssignmentEntity a where a.siteId = :" + prefix + "site and ("
                        + inLists + "))";
            case NOT_IN ->
                postId + " not in (select a.postId from TermAssignmentEntity a where a.siteId = :" + prefix
                        + "site and (" + inLists + "))";
            case AND -> {
                params.put(prefix + "count", (long) resolved.size());
                yield "(select count(distinct a.termId) from TermAssignmentEntity a where a.siteId = :" + prefix
                        + "site and a.postId = " + postId + " and (" + inLists + ")) = :" + prefix + "count";
            }
        };
        return new TermCondition.Jpql(text, params);
    }

    /**
     * {@code column in :name0 or column in :name1 ...}, each list holding at most {@link #CHUNK} ids, the lists put
     * into {@code params}; there are {@code ceil(n / CHUNK)} lists.
     */
    static String chunked(String column, List<Long> ids, String name, Map<String, Object> params) {
        StringBuilder text = new StringBuilder();
        for (int chunk = 0, from = 0; from < ids.size(); chunk++, from += CHUNK) {
            String param = name + chunk;
            params.put(param, List.copyOf(ids.subList(from, Math.min(from + CHUNK, ids.size()))));
            if (chunk > 0) {
                text.append(" or ");
            }
            text.append(column).append(" in :").append(param);
        }
        return text.toString();
    }

    /** The listed ids that are terms of the taxonomy on the site, in the listed order. */
    private List<Long> resolve(SiteId site, String taxonomy, Set<Long> listed) {
        if (listed.isEmpty()) {
            return List.of();
        }
        Set<Long> found = new LinkedHashSet<>(idsWhere(site, taxonomy, "t.id", new ArrayList<>(listed)));
        List<Long> resolved = new ArrayList<>();
        for (Long id : listed) {
            if (found.contains(id)) {
                resolved.add(id);
            }
        }
        return resolved;
    }

    /**
     * The terms and all their descendants in the taxonomy, read one level at a time: each level's query names the
     * level above as parents and stops when a level adds no term, so a loop in the stored parents ends the walk too.
     * A walk that passes the cap stops at that level with the refusal.
     */
    private List<Long> withDescendants(SiteId site, String taxonomy, List<Long> roots) {
        Set<Long> seen = new LinkedHashSet<>(roots);
        List<Long> level = roots;
        while (!level.isEmpty()) {
            List<Long> next = new ArrayList<>();
            for (Long child : idsWhere(site, taxonomy, "t.parentId", level)) {
                if (seen.add(child)) {
                    next.add(child);
                }
            }
            if (seen.size() > maxExpandedIds) {
                throw TermException.filterTooLarge();
            }
            level = next;
        }
        return new ArrayList<>(seen);
    }

    /** The ids of the taxonomy's terms on the site whose {@code column} is among {@code values}, one query. */
    private List<Long> idsWhere(SiteId site, String taxonomy, String column, List<Long> values) {
        Map<String, Object> params = new LinkedHashMap<>();
        String lists = chunked(column, values, "v", params);
        EntityManager entityManager = terms.getEntityManager();
        var query = entityManager
                .createQuery(
                        "select t.id from TermEntity t where t.siteId = :site and t.taxonomy = :taxonomy and (" + lists
                                + ") order by t.id",
                        Long.class)
                .setParameter("site", TenantIds.param(site))
                .setParameter("taxonomy", taxonomy);
        params.forEach(query::setParameter);
        return query.getResultList();
    }
}
