package org.quarkpress.taxonomy.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.TypedQuery;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.core.TermId;
import org.quarkpress.persistence.runtime.Dialects;
import org.quarkpress.persistence.runtime.TenantIds;
import org.quarkpress.taxonomy.TermOrder;
import org.quarkpress.taxonomy.TermQuery;
import org.quarkpress.taxonomy.TermSnapshot;

/** WordPress's {@code WP_Term_Query}: the query's SQL part, then the hierarchy steps WordPress takes in PHP. */
@ApplicationScoped
public class TermQueries {

    @Inject
    TermRepository terms;

    @Inject
    TermLookups lookups;

    @Inject
    TermSlugs slugs;

    /** WordPress's adjustments of the arguments. */
    private record Effective(
            boolean hierarchical, boolean padCounts, boolean hideEmpty, long childOf, boolean childless) {}

    /** An HQL condition list and its parameters. */
    private static final class Where {
        final StringBuilder text = new StringBuilder();
        final Map<String, Object> params = new LinkedHashMap<>();

        void and(String condition) {
            text.append(text.isEmpty() ? "" : " and ")
                    .append('(')
                    .append(condition)
                    .append(')');
        }

        String param(Object value) {
            String name = "p" + params.size();
            params.put(name, value);
            return ":" + name;
        }
    }

    public List<TermSnapshot> run(
            SiteId site, List<TaxonomyDefinition> taxonomies, boolean taxonomiesGiven, TermQuery query) {
        if (taxonomies.isEmpty()) {
            return List.of();
        }
        Effective effective = effective(taxonomies, taxonomiesGiven, query, false);
        Map<String, Map<TermId, List<TermId>>> hierarchy =
                hierarchy(site, taxonomies, taxonomiesGiven, effective, query);
        List<TaxonomyDefinition> named = taxonomiesGiven ? taxonomies : List.of();
        Map<String, Map<TermId, List<TermId>>> steps = taxonomiesGiven ? hierarchy : Map.of();
        if (noChildren(effective, query, steps)) {
            return List.of();
        }
        List<String> slugList = sanitizedSlugs(site, taxonomies, query);
        Where where = where(site, taxonomies, named, query, effective, steps, slugList);
        boolean ascending = TermOrders.ascending(query.order());
        int number = absolute(query.number());
        int offset = absolute(query.offset());
        boolean limitInSql = number > 0
                && !effective.hierarchical()
                && effective.childOf() == 0
                && query.parent().isEmpty();
        TypedQuery<TermEntity> select = terms.getEntityManager()
                .createQuery(
                        "select t from TermEntity t where " + where.text + " order by "
                                + order(query, slugList, where, ascending),
                        TermEntity.class);
        where.params.forEach(select::setParameter);
        if (limitInSql) {
            select.setFirstResult(offset).setMaxResults(number);
        }
        List<TermSnapshot> results =
                select.getResultStream().map(TermEntity::toSnapshot).toList();
        if (effective.childOf() > 0) {
            for (TaxonomyDefinition taxonomy : named) {
                Map<TermId, List<TermId>> children = steps.getOrDefault(taxonomy.key(), Map.of());
                if (!children.isEmpty()) {
                    results = descendants(TermId.of(effective.childOf()), results, children);
                }
            }
        }
        if (effective.padCounts()) {
            results = padded(site, named, results, steps);
        }
        if (effective.hierarchical() && effective.hideEmpty()) {
            results = keepCounted(site, results, hierarchy);
        }
        if (effective.hierarchical() && number > 0) {
            results = offset >= results.size()
                    ? List.of()
                    : results.subList(offset, (int) Math.min(results.size(), (long) offset + number));
        }
        return List.copyOf(results);
    }

    public long count(SiteId site, List<TaxonomyDefinition> taxonomies, boolean taxonomiesGiven, TermQuery query) {
        if (taxonomies.isEmpty()) {
            return 0;
        }
        Effective effective = effective(taxonomies, taxonomiesGiven, query, true);
        Map<String, Map<TermId, List<TermId>>> steps =
                taxonomiesGiven ? hierarchy(site, taxonomies, true, effective, query) : Map.of();
        if (noChildren(effective, query, steps)) {
            return 0;
        }
        Where where = where(
                site,
                taxonomies,
                taxonomiesGiven ? taxonomies : List.of(),
                query,
                effective,
                steps,
                sanitizedSlugs(site, taxonomies, query));
        // with posts, WordPress counts the term-post pairs among those posts, not the terms
        String select = query.posts().isEmpty()
                ? "select count(t) from TermEntity t where " + where.text
                : "select count(a) from TermEntity t, TermAssignmentEntity a where a.siteId = t.siteId"
                        + " and a.termId = t.id and a.postId in "
                        + where.param(query.posts().stream().map(PostId::value).toList()) + " and " + where.text;
        TypedQuery<Long> count = terms.getEntityManager().createQuery(select, Long.class);
        where.params.forEach(count::setParameter);
        return count.getSingleResult();
    }

    /**
     * WordPress's adjustments: no hierarchy or padding when no queried taxonomy is hierarchical (when none is named,
     * the tree is assumed); a positive parent cancels childOf; {@code all} turns hiding, hierarchy, padding, childOf
     * and childless off; posts turn hiding off; a count is never hierarchical. Hiding not set by the caller is on for
     * a query and off for a count, as in WordPress's term query and term count.
     */
    private static Effective effective(
            List<TaxonomyDefinition> taxonomies, boolean taxonomiesGiven, TermQuery query, boolean counting) {
        boolean tree = !taxonomiesGiven || taxonomies.stream().anyMatch(TaxonomyDefinition::hierarchical);
        boolean hierarchical = query.hierarchical() && tree && !counting;
        boolean padCounts = query.padCounts() && tree;
        boolean hideEmpty = query.hideEmpty().orElse(!counting) && query.posts().isEmpty();
        long childOf = query.parent().orElse(0) > 0 ? 0 : query.childOf();
        boolean childless = query.childless();
        if (query.all()) {
            return new Effective(false, false, false, 0, false);
        }
        return new Effective(hierarchical, padCounts, hideEmpty, childOf, childless);
    }

    /**
     * Each queried hierarchical taxonomy's children by parent, read only when a step of the query needs them; otherwise
     * none. The parent or childOf probe, exclude_tree, childless and padding walk only the taxonomies the query names
     * (WordPress walks none for a query without taxonomies); hiding in a hierarchical query walks each term's own.
     */
    private Map<String, Map<TermId, List<TermId>>> hierarchy(
            SiteId site,
            List<TaxonomyDefinition> taxonomies,
            boolean taxonomiesGiven,
            Effective effective,
            TermQuery query) {
        Map<String, Map<TermId, List<TermId>>> hierarchy = new HashMap<>();
        boolean steps = probe(effective, query) > 0
                || (query.include().isEmpty() && !query.excludeTree().isEmpty())
                || effective.childless()
                || effective.padCounts();
        boolean needed = (taxonomiesGiven && steps) || (effective.hierarchical() && effective.hideEmpty());
        if (!needed) {
            return hierarchy;
        }
        for (TaxonomyDefinition taxonomy : taxonomies) {
            hierarchy.put(taxonomy.key(), lookups.children(site, taxonomy));
        }
        return hierarchy;
    }

    /** The term whose children the query needs: its childOf, else its parent; 0 for none. */
    private static long probe(Effective effective, TermQuery query) {
        return effective.childOf() != 0 ? effective.childOf() : query.parent().orElse(0);
    }

    /**
     * A childOf or parent that is nobody's parent in the named taxonomies answers nothing at once (so any positive one
     * does when the query names none); a negative one is nobody's parent.
     */
    private static boolean noChildren(
            Effective effective, TermQuery query, Map<String, Map<TermId, List<TermId>>> hierarchy) {
        long probe = probe(effective, query);
        if (probe == 0) {
            return false;
        }
        if (probe < 0 || query.parent().orElse(0) < 0) {
            return true;
        }
        TermId parent = TermId.of(probe);
        return hierarchy.values().stream().noneMatch(children -> children.containsKey(parent));
    }

    /** The query's slugs, each sanitized as a slug (WordPress sanitizes them before it compares). */
    private List<String> sanitizedSlugs(SiteId site, List<TaxonomyDefinition> taxonomies, TermQuery query) {
        String key = taxonomies.isEmpty() ? "" : taxonomies.get(0).key();
        return query.slugs().stream()
                .map(slug -> slugs.sanitize(site, key, slug))
                .toList();
    }

    /**
     * The query's conditions. exclude_tree takes the descendants from the first named taxonomy (none when the query
     * names none, so only the trunks are excluded); childless excludes the parents of the named taxonomies.
     */
    private Where where(
            SiteId site,
            List<TaxonomyDefinition> taxonomies,
            List<TaxonomyDefinition> named,
            TermQuery query,
            Effective effective,
            Map<String, Map<TermId, List<TermId>>> hierarchy,
            List<String> slugList) {
        Where where = new Where();
        where.and("t.siteId = " + where.param(TenantIds.param(site)));
        where.and("t.taxonomy in "
                + where.param(taxonomies.stream().map(TaxonomyDefinition::key).toList()));
        Set<Long> exclusions = new LinkedHashSet<>();
        if (!query.include().isEmpty()) {
            where.and("t.id in " + where.param(ids(query.include())));
        } else {
            if (!query.excludeTree().isEmpty()) {
                Map<TermId, List<TermId>> first = named.isEmpty()
                        ? Map.of()
                        : hierarchy.getOrDefault(named.get(0).key(), Map.of());
                for (TermId trunk : query.excludeTree()) {
                    exclusions.add(trunk.value());
                    TermHierarchy.children(trunk, first).forEach(id -> exclusions.add(id.value()));
                }
            }
            query.exclude().forEach(id -> exclusions.add(id.value()));
        }
        if (effective.childless()) {
            hierarchy.values().forEach(children -> children.keySet().forEach(id -> exclusions.add(id.value())));
        }
        if (!exclusions.isEmpty()) {
            where.and("t.id not in " + where.param(List.copyOf(exclusions)));
        }
        if (!query.names().isEmpty()) {
            List<String> conditions = new ArrayList<>();
            for (String name : query.names()) {
                conditions.add("lower(t.name) = lower(" + where.param(TermText.name(name)) + ")");
            }
            where.and(String.join(" or ", conditions));
        }
        if (!slugList.isEmpty()) {
            where.and("t.slug in " + where.param(slugList));
        }
        query.search().ifPresent(search -> {
            String like = where.param(like(search));
            where.and("lower(t.name) like lower(" + like + ") escape '!' or lower(t.slug) like lower(" + like
                    + ") escape '!'");
        });
        query.nameLike()
                .ifPresent(text -> where.and("lower(t.name) like lower(" + where.param(like(text)) + ") escape '!'"));
        query.descriptionLike()
                .ifPresent(text ->
                        where.and("lower(t.description) like lower(" + where.param(like(text)) + ") escape '!'"));
        if (!query.posts().isEmpty()) {
            where.and("exists (select 1 from TermAssignmentEntity a where a.siteId = t.siteId and a.termId = t.id"
                    + " and a.postId in "
                    + where.param(query.posts().stream().map(PostId::value).toList()) + ")");
        }
        if (query.parent().isPresent()) {
            long parent = query.parent().getAsLong();
            where.and(parent > 0 ? "t.parentId = " + where.param(parent) : "t.parentId is null");
        }
        if (effective.hideEmpty() && !effective.hierarchical()) {
            where.and("t.termCount > 0");
        }
        return where;
    }

    /** The ORDER BY of the query: its key with WordPress's fallbacks, then the id in the same direction. */
    private String order(TermQuery query, List<String> slugList, Where where, boolean ascending) {
        TermOrder key = query.orderBy();
        if (key == TermOrder.INCLUDE && query.include().isEmpty()) {
            key = TermOrder.NAME;
        }
        if (key == TermOrder.SLUG_IN && query.slugs().isEmpty()) {
            key = TermOrder.NAME;
        }
        if (key == TermOrder.TERM_ORDER && query.posts().isEmpty()) {
            key = TermOrder.ID;
        }
        String direction = ascending ? " asc" : " desc";
        boolean postgresql = Dialects.postgresql(terms.getSession());
        String expression = switch (key) {
            case NAME -> lookups.nameOrder("t");
            case SLUG -> codePoints("t.slug", postgresql);
            case DESCRIPTION -> codePoints("lower(t.description)", postgresql);
            case TAXONOMY -> codePoints("t.taxonomy", postgresql);
            case COUNT -> "t.termCount";
            case PARENT -> "coalesce(t.parentId, 0)";
            case INCLUDE -> position("t.id", ids(query.include()), where);
            case SLUG_IN -> position("t.slug", slugList, where);
            case TERM_ORDER ->
                "(select min(a.termOrder) from TermAssignmentEntity a where a.siteId = t.siteId"
                        + " and a.termId = t.id and a.postId in "
                        + where.param(query.posts().stream().map(PostId::value).toList()) + ")";
            case ID, TERM_GROUP, NONE -> "t.id";
        };
        if (key == TermOrder.NONE) {
            return "t.id asc";
        }
        return expression + direction + ", t.id" + direction;
    }

    private static String codePoints(String expression, boolean postgresql) {
        return postgresql ? "collate(" + expression + " as ucs_basic)" : expression;
    }

    private static String position(String column, List<?> values, Where where) {
        StringBuilder position = new StringBuilder("case");
        for (int i = 0; i < values.size(); i++) {
            position.append(" when ").append(column).append(" = ").append(where.param(values.get(i)));
            position.append(" then ").append(i);
        }
        return position.append(" else ").append(values.size()).append(" end").toString();
    }

    /**
     * WordPress's childOf step: the descendants of {@code parent} among the results, depth first, siblings in result
     * order.
     */
    private static List<TermSnapshot> descendants(
            TermId parent, List<TermSnapshot> results, Map<TermId, List<TermId>> children) {
        if (!children.containsKey(parent)) {
            // the taxonomy has a hierarchy, but the term is nobody's parent in it: nothing is below it
            return List.of();
        }
        List<TermSnapshot> out = new ArrayList<>();
        Set<TermId> ancestors = new HashSet<>();
        ancestors.add(parent);
        collect(parent, results, children, ancestors, out);
        return out;
    }

    private static void collect(
            TermId parent,
            List<TermSnapshot> results,
            Map<TermId, List<TermId>> children,
            Set<TermId> ancestors,
            List<TermSnapshot> out) {
        for (TermSnapshot term : results) {
            if (ancestors.contains(term.id()) || !term.parent().equals(Optional.of(parent))) {
                continue;
            }
            out.add(term);
            if (children.containsKey(term.id())) {
                ancestors.add(term.id());
                collect(term.id(), results, children, ancestors, out);
            }
        }
    }

    /**
     * WordPress's padded counts: for each hierarchical taxonomy with a hierarchy in turn, every result term's count
     * becomes the number of distinct published posts of that taxonomy's types in it or in a result term below it. A term
     * no such post touches keeps the count it had, so a later taxonomy's pass overrides an earlier one's.
     */
    private List<TermSnapshot> padded(
            SiteId site,
            List<TaxonomyDefinition> taxonomies,
            List<TermSnapshot> results,
            Map<String, Map<TermId, List<TermId>>> hierarchy) {
        Map<TermId, Integer> counts = new HashMap<>();
        Map<TermId, TermSnapshot> byId = new HashMap<>();
        results.forEach(term -> byId.put(term.id(), term));
        List<Long> ids = results.stream().map(term -> term.id().value()).toList();
        for (TaxonomyDefinition taxonomy : taxonomies) {
            if (!taxonomy.hierarchical()
                    || hierarchy.getOrDefault(taxonomy.key(), Map.of()).isEmpty()
                    || ids.isEmpty()
                    || taxonomy.objectTypes().isEmpty()) {
                continue;
            }
            Map<TermId, Set<Long>> items = new HashMap<>();
            terms.getEntityManager()
                    .createQuery(
                            "select a.termId, a.postId from TermAssignmentEntity a, PostEntity p where a.siteId = :site"
                                    + " and p.siteId = :site and p.id = a.postId and a.termId in :ids"
                                    + " and p.type in :types and p.status = 'publish'",
                            Object[].class)
                    .setParameter("site", TenantIds.param(site))
                    .setParameter("ids", ids)
                    .setParameter("types", taxonomy.objectTypes())
                    .getResultStream()
                    .forEach(row -> items.computeIfAbsent(TermId.of((Long) row[0]), id -> new HashSet<>())
                            .add((Long) row[1]));
            Map<TermId, Set<Long>> touched = new HashMap<>();
            for (Map.Entry<TermId, Set<Long>> entry : items.entrySet()) {
                touched.computeIfAbsent(entry.getKey(), id -> new HashSet<>()).addAll(entry.getValue());
                Set<TermId> walked = new HashSet<>();
                TermSnapshot current = byId.get(entry.getKey());
                while (current != null && current.parent().isPresent() && walked.add(current.id())) {
                    TermId parent = current.parent().get();
                    touched.computeIfAbsent(parent, id -> new HashSet<>()).addAll(entry.getValue());
                    current = byId.get(parent);
                }
            }
            touched.forEach((id, posts) -> {
                if (byId.containsKey(id)) {
                    counts.put(id, posts.size());
                }
            });
        }
        if (counts.isEmpty()) {
            return results;
        }
        return results.stream()
                .map(term -> counts.containsKey(term.id())
                        ? new TermSnapshot(
                                term.id(),
                                term.site(),
                                term.taxonomy(),
                                term.name(),
                                term.slug(),
                                term.description(),
                                term.parent(),
                                counts.get(term.id()),
                                term.version())
                        : term)
                .toList();
    }

    /** WordPress's hiding in a hierarchical query: a term without count stays only when a descendant has one. */
    private List<TermSnapshot> keepCounted(
            SiteId site, List<TermSnapshot> results, Map<String, Map<TermId, List<TermId>>> hierarchy) {
        Map<TermId, List<TermId>> descendantsOfEmpty = new HashMap<>();
        Set<Long> wanted = new HashSet<>();
        for (TermSnapshot term : results) {
            if (term.count() == 0) {
                List<TermId> descendants =
                        TermHierarchy.children(term.id(), hierarchy.getOrDefault(term.taxonomy(), Map.of()));
                descendantsOfEmpty.put(term.id(), descendants);
                descendants.forEach(id -> wanted.add(id.value()));
            }
        }
        Set<TermId> counted = new HashSet<>();
        if (!wanted.isEmpty()) {
            terms.getEntityManager()
                    .createQuery(
                            "select t.id from TermEntity t where t.siteId = :site and t.id in :ids"
                                    + " and t.termCount > 0",
                            Long.class)
                    .setParameter("site", TenantIds.param(site))
                    .setParameter("ids", List.copyOf(wanted))
                    .getResultStream()
                    .forEach(id -> counted.add(TermId.of(id)));
        }
        List<TermSnapshot> kept = new ArrayList<>();
        for (TermSnapshot term : results) {
            if (term.count() > 0
                    || descendantsOfEmpty.getOrDefault(term.id(), List.of()).stream()
                            .anyMatch(counted::contains)) {
                kept.add(term);
            }
        }
        return kept;
    }

    /** WordPress's absolute value of a count: the magnitude, never negative, capped at the largest int. */
    private static int absolute(int value) {
        return value == Integer.MIN_VALUE ? Integer.MAX_VALUE : Math.abs(value);
    }

    private static List<Long> ids(List<TermId> ids) {
        return ids.stream().map(TermId::value).toList();
    }

    /** {@code %text%} with {@code !}, {@code %} and {@code _} escaped by {@code !}, so they match literally. */
    static String like(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('%');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '!' || c == '%' || c == '_') {
                out.append('!');
            }
            out.append(c);
        }
        return out.append('%').toString();
    }
}
