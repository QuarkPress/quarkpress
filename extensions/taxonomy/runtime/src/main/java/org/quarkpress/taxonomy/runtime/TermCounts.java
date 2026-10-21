package org.quarkpress.taxonomy.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.core.TermId;
import org.quarkpress.persistence.runtime.TenantIds;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.taxonomy.Taxonomies;

/**
 * Term counts as WordPress computes them and stores them. A taxonomy attached only to registered post types counts the
 * related posts of those types that are published, plus, when attachments are among its types, related attachments
 * that are published or that inherit from a published parent; posts of other types are not counted. Any other taxonomy
 * counts every related object. Count changes never touch a term's version.
 */
@ApplicationScoped
public class TermCounts {

    /** The statuses a post is counted in. */
    public static final List<String> COUNTED_STATUSES = List.of("publish");

    @Inject
    TermRepository terms;

    @Inject
    Taxonomies taxonomies;

    @Inject
    PostTypes postTypes;

    /**
     * Recomputes the counts of {@code ids}, locking the terms in ascending id order. Terms of an unregistered taxonomy
     * keep their count.
     */
    public void recount(SiteId site, Collection<TermId> ids) {
        List<TermEntity> rows = terms.lockTerms(site, new LinkedHashSet<>(ids));
        for (TermEntity row : rows) {
            int count = count(site, row);
            if (row.termCount != count) {
                row.termCount = count;
            }
        }
        terms.flush();
    }

    /**
     * The taxonomy's object types as WordPress counts them: an {@code attachment:<mime>} type counts as
     * {@code attachment}, each type once.
     */
    static List<String> countedTypes(TaxonomyDefinition taxonomy) {
        LinkedHashSet<String> types = new LinkedHashSet<>();
        for (String type : taxonomy.objectTypes()) {
            types.add(type.startsWith("attachment:") ? "attachment" : type);
        }
        return List.copyOf(types);
    }

    /** What the term's count should be. */
    public int count(SiteId site, TermEntity term) {
        Optional<TaxonomyDefinition> taxonomy = taxonomies.find(term.taxonomy);
        if (taxonomy.isEmpty()) {
            return term.termCount;
        }
        List<String> types = countedTypes(taxonomy.get());
        if (!types.stream().allMatch(postTypes::registered)) {
            return Math.toIntExact(terms.getEntityManager()
                    .createQuery(
                            "select count(a) from TermAssignmentEntity a where a.siteId = :site and a.termId = :term",
                            Long.class)
                    .setParameter("site", TenantIds.param(site))
                    .setParameter("term", term.id)
                    .getSingleResult());
        }
        List<String> posts =
                types.stream().filter(type -> !type.equals("attachment")).toList();
        boolean attachments = types.contains("attachment");
        if (posts.isEmpty() && !attachments) {
            return 0;
        }
        StringBuilder counted = new StringBuilder();
        if (!posts.isEmpty()) {
            counted.append("(p.type in :types and p.status in :counted)");
        }
        if (attachments) {
            counted.append(counted.isEmpty() ? "" : " or ")
                    .append("(p.type = 'attachment' and (p.status in :counted or (p.status = 'inherit'")
                    .append(" and p.parentId is not null and exists (select 1 from PostEntity q")
                    .append(" where q.siteId = :site and q.id = p.parentId and q.status in :counted))))");
        }
        var query = terms.getEntityManager()
                .createQuery(
                        "select count(distinct p.id) from TermAssignmentEntity a, PostEntity p"
                                + " where a.siteId = :site and p.siteId = :site and p.id = a.postId"
                                + " and a.termId = :term and (" + counted + ")",
                        Long.class)
                .setParameter("site", TenantIds.param(site))
                .setParameter("term", term.id)
                .setParameter("counted", COUNTED_STATUSES);
        if (!posts.isEmpty()) {
            query.setParameter("types", posts);
        }
        return Math.toIntExact(query.getSingleResult());
    }
}
