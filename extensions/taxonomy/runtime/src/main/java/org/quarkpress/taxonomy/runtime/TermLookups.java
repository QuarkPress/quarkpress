package org.quarkpress.taxonomy.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.TypedQuery;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.core.TermId;
import org.quarkpress.persistence.runtime.Dialects;
import org.quarkpress.persistence.runtime.TenantIds;
import org.quarkpress.taxonomy.Taxonomies;

/**
 * The reads of the term write path. Names compare ignoring case through the databases' {@code lower()} (accents
 * count); slugs compare exactly; names are ordered by the code points of their lower-cased form, then by id.
 */
@ApplicationScoped
public class TermLookups {

    @Inject
    TermRepository terms;

    @Inject
    TermSlugs slugs;

    @Inject
    Taxonomies taxonomies;

    public Optional<TermEntity> find(SiteId site, TermId id, String taxonomy) {
        return terms.findTerm(site, id, taxonomy);
    }

    /**
     * The taxonomy's terms under {@code parent} (top level when empty) whose name has as many characters as
     * {@code name}, in name order: the only terms whose name can equal it after lowering the letters A to Z.
     */
    public List<DuplicateNames.Stored> sameLength(SiteId site, String taxonomy, Optional<TermId> parent, String name) {
        TypedQuery<TermEntity> query = terms.getEntityManager()
                .createQuery(
                        "select t from TermEntity t where t.siteId = :site and t.taxonomy = :taxonomy"
                                + " and length(t.name) = :length and "
                                + (parent.isPresent() ? "t.parentId = :parent" : "t.parentId is null")
                                + " order by " + nameOrder("t") + ", t.id",
                        TermEntity.class)
                .setParameter("site", TenantIds.param(site))
                .setParameter("taxonomy", taxonomy)
                .setParameter("length", name.codePointCount(0, name.length()));
        parent.ifPresent(id -> query.setParameter("parent", id.value()));
        return query.getResultStream()
                .map(term -> new DuplicateNames.Stored(
                        TermId.of(term.id),
                        term.name,
                        term.slug,
                        Optional.ofNullable(term.parentId).map(TermId::of)))
                .toList();
    }

    /** The taxonomy's term holding {@code slug} exactly. */
    public Optional<TermEntity> bySlug(SiteId site, String taxonomy, String slug) {
        return terms.find("siteId = ?1 and taxonomy = ?2 and slug = ?3", TenantIds.param(site), taxonomy, slug)
                .firstResultOptional();
    }

    /**
     * The taxonomy's first term (lowest id) whose name equals {@code text} filtered as WordPress filters a name it
     * looks up (see {@link TermText#probeName}), ignoring case.
     */
    public Optional<TermEntity> byName(SiteId site, String taxonomy, String text) {
        return byName(site, taxonomy, text, OptionalLong.empty());
    }

    /**
     * WordPress's {@code term_exists} for text: the text trimmed (a NUL too), looked up as a slug (made as for a stored
     * name's slug), then as a name; the lowest id wins; empty text finds nothing. With {@code parent} (0: top level),
     * only terms with that parent count; as in WordPress, any other parent finds nothing in a flat taxonomy, and a
     * negative one finds nothing anywhere.
     */
    public Optional<TermEntity> exists(SiteId site, String taxonomy, String text, OptionalLong parent) {
        String probe = TermText.trimmed(text);
        if (probe.isEmpty() || !parentPossible(taxonomy, parent)) {
            return Optional.empty();
        }
        TypedQuery<TermEntity> bySlug = terms.getEntityManager()
                .createQuery(
                        "select t from TermEntity t where t.siteId = :site and t.taxonomy = :taxonomy"
                                + " and t.slug = :slug" + parentCondition(parent) + " order by t.id",
                        TermEntity.class)
                .setParameter("site", TenantIds.param(site))
                .setParameter("taxonomy", taxonomy)
                .setParameter("slug", slugs.generate(site, taxonomy, probe))
                .setMaxResults(1);
        bindParent(bySlug, parent);
        Optional<TermEntity> found = bySlug.getResultStream().findFirst();
        return found.isPresent() ? found : byName(site, taxonomy, probe, parent);
    }

    private boolean parentPossible(String taxonomy, OptionalLong parent) {
        if (parent.isEmpty() || parent.getAsLong() == 0) {
            return true;
        }
        return parent.getAsLong() > 0
                && taxonomies
                        .find(taxonomy)
                        .map(TaxonomyDefinition::hierarchical)
                        .orElse(false);
    }

    private Optional<TermEntity> byName(SiteId site, String taxonomy, String text, OptionalLong parent) {
        String name = TermText.probeName(text);
        if (name.isEmpty()) {
            return Optional.empty();
        }
        TypedQuery<TermEntity> query = terms.getEntityManager()
                .createQuery(
                        "select t from TermEntity t where t.siteId = :site and t.taxonomy = :taxonomy"
                                + " and lower(t.name) = lower(:name)" + parentCondition(parent) + " order by t.id",
                        TermEntity.class)
                .setParameter("site", TenantIds.param(site))
                .setParameter("taxonomy", taxonomy)
                .setParameter("name", name)
                .setMaxResults(1);
        bindParent(query, parent);
        return query.getResultStream().findFirst();
    }

    private static String parentCondition(OptionalLong parent) {
        if (parent.isEmpty()) {
            return "";
        }
        return parent.getAsLong() > 0 ? " and t.parentId = :parent" : " and t.parentId is null";
    }

    private static void bindParent(TypedQuery<?> query, OptionalLong parent) {
        if (parent.isPresent() && parent.getAsLong() > 0) {
            query.setParameter("parent", parent.getAsLong());
        }
    }

    /** The reads of the unique-slug rule for terms of {@code taxonomy} on {@code site}. */
    public UniqueTermSlugs.Lookup uniqueLookup(SiteId site, String taxonomy) {
        return new UniqueTermSlugs.Lookup() {
            @Override
            public boolean takenInTaxonomy(String slug) {
                return bySlug(site, taxonomy, slug).isPresent();
            }

            @Override
            public Optional<UniqueTermSlugs.Parent> parent(TermId id) {
                return terms.findTerm(site, id, taxonomy)
                        .map(term -> new UniqueTermSlugs.Parent(
                                term.slug, Optional.ofNullable(term.parentId).map(TermId::of)));
            }

            @Override
            public boolean existsAnywhere(String slugOrName) {
                String probe = TermText.trimmed(slugOrName);
                if (probe.isEmpty()) {
                    return false;
                }
                String name = TermText.probeName(probe);
                return terms.count(
                                "siteId = ?1 and (slug = ?2 or lower(name) = lower(?3))",
                                TenantIds.param(site),
                                slugs.generate(site, taxonomy, probe),
                                name)
                        > 0;
            }

            @Override
            public boolean slugTakenAnywhere(String slug, Optional<TermId> except) {
                return except.isPresent()
                        ? terms.count(
                                        "siteId = ?1 and slug = ?2 and id <> ?3",
                                        TenantIds.param(site),
                                        slug,
                                        except.get().value())
                                > 0
                        : terms.count("siteId = ?1 and slug = ?2", TenantIds.param(site), slug) > 0;
            }
        };
    }

    /**
     * The children of each term of a hierarchical taxonomy, by ascending id; empty for a flat taxonomy, as WordPress
     * keeps no hierarchy for one.
     */
    public Map<TermId, List<TermId>> children(SiteId site, TaxonomyDefinition taxonomy) {
        Map<TermId, List<TermId>> children = new LinkedHashMap<>();
        if (!taxonomy.hierarchical()) {
            return children;
        }
        terms.getEntityManager()
                .createQuery(
                        "select t.id, t.parentId from TermEntity t where t.siteId = :site and t.taxonomy = :taxonomy"
                                + " and t.parentId is not null order by t.id",
                        Object[].class)
                .setParameter("site", TenantIds.param(site))
                .setParameter("taxonomy", taxonomy.key())
                .getResultStream()
                .forEach(row -> children.computeIfAbsent(TermId.of((Long) row[1]), parent -> new ArrayList<>())
                        .add(TermId.of((Long) row[0])));
        children.replaceAll((parent, list) -> List.copyOf(list));
        return children;
    }

    /**
     * The stored parent of a term of the taxonomy; empty for a top-level term, for a term of another taxonomy and for
     * a parent that is not a term of the same taxonomy.
     */
    public Optional<TermId> storedParent(SiteId site, String taxonomy, TermId term) {
        return terms.findTerm(site, term, taxonomy)
                .flatMap(row -> Optional.ofNullable(row.parentId))
                .map(TermId::of)
                .filter(parent -> terms.findTerm(site, parent, taxonomy).isPresent());
    }

    /**
     * The name order of WordPress's term queries as QuarkPress keeps it on both databases: the code points of the
     * lower-cased name (MySQL's columns compare that way; PostgreSQL compares under {@code ucs_basic}).
     */
    public String nameOrder(String alias) {
        String lower = "lower(" + alias + ".name)";
        return Dialects.postgresql(terms.getSession()) ? "collate(" + lower + " as ucs_basic)" : lower;
    }
}
