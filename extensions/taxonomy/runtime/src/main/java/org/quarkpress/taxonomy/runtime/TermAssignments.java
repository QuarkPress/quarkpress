package org.quarkpress.taxonomy.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.quarkpress.core.PostId;
import org.quarkpress.core.QuarkPressClock;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.core.TermId;
import org.quarkpress.persistence.runtime.TenantIds;

/**
 * Assignments of terms to posts, without capability checks: the caller holds the site's post lock. Every change
 * recomputes the counts of the terms it adds or removes.
 */
@ApplicationScoped
public class TermAssignments {

    @Inject
    TermAssignmentRepository rows;

    @Inject
    TermCounts counts;

    @Inject
    TermEvents events;

    @Inject
    QuarkPressClock clock;

    /** The post's terms of {@code taxonomy}, by ascending id. */
    public List<TermId> termsOf(SiteId site, PostId post, String taxonomy) {
        return rows.getEntityManager()
                .createQuery(
                        "select a.termId from TermAssignmentEntity a, TermEntity t where a.siteId = :site"
                                + " and t.siteId = :site and t.id = a.termId and a.postId = :post"
                                + " and t.taxonomy = :taxonomy order by a.termId",
                        Long.class)
                .setParameter("site", TenantIds.param(site))
                .setParameter("post", post.value())
                .setParameter("taxonomy", taxonomy)
                .getResultStream()
                .map(TermId::of)
                .toList();
    }

    /** The post's terms of every taxonomy, by ascending id. */
    public List<TermId> allTermsOf(SiteId site, PostId post) {
        return rows.ofPost(site, post).stream()
                .map(row -> TermId.of(row.termId))
                .toList();
    }

    /**
     * WordPress's {@code wp_set_object_terms} with the terms already resolved ({@code requested} in input order,
     * duplicates included): new assignments get order 0, then a replace removes the post's other terms of the taxonomy
     * and, for a sorted taxonomy, numbers the requested terms 1, 2, 3, ... in input order (a term given twice keeps its
     * last position). Counts of added and removed terms are recomputed and the change is announced.
     */
    public void set(SiteId site, PostId post, TaxonomyDefinition taxonomy, List<TermId> requested, boolean append) {
        Instant now = clock.now().truncatedTo(ChronoUnit.MICROS);
        List<TermId> before = termsOf(site, post, taxonomy.key());
        Set<TermId> wanted = new LinkedHashSet<>(requested);
        List<TermId> changed = new ArrayList<>();
        for (TermId term : wanted) {
            if (!before.contains(term)) {
                rows.persist(TermAssignmentEntity.create(site, post, term, 0));
                changed.add(term);
            }
        }
        if (!append) {
            for (TermId term : before) {
                if (!wanted.contains(term)) {
                    rows.find(site, post, term).ifPresent(rows::delete);
                    changed.add(term);
                }
            }
            if (taxonomy.sort()) {
                Map<TermId, Integer> order = new HashMap<>();
                int position = 0;
                for (TermId term : requested) {
                    order.put(term, ++position);
                }
                rows.flush();
                order.forEach((term, value) -> rows.find(site, post, term).ifPresent(row -> row.termOrder = value));
            }
        }
        rows.flush();
        counts.recount(site, changed);
        events.assigned(site, now, post, taxonomy.key(), before, termsOf(site, post, taxonomy.key()));
    }

    /**
     * WordPress's {@code wp_remove_object_terms} with the terms already resolved: true when an assignment was removed;
     * then the counts are recomputed and the change announced.
     */
    public boolean remove(SiteId site, PostId post, String taxonomy, Collection<TermId> terms) {
        Instant now = clock.now().truncatedTo(ChronoUnit.MICROS);
        List<TermId> before = termsOf(site, post, taxonomy);
        List<TermId> removed = new ArrayList<>();
        for (TermId term : new LinkedHashSet<>(terms)) {
            if (before.contains(term)) {
                rows.find(site, post, term).ifPresent(rows::delete);
                removed.add(term);
            }
        }
        if (removed.isEmpty()) {
            return false;
        }
        rows.flush();
        counts.recount(site, removed);
        events.assigned(site, now, post, taxonomy, before, termsOf(site, post, taxonomy));
        return true;
    }

    /** Removes every assignment of a post about to be deleted and recomputes the counts; nothing is announced. */
    public void forgetPost(SiteId site, PostId post) {
        List<TermAssignmentEntity> assigned = rows.ofPost(site, post);
        if (assigned.isEmpty()) {
            return;
        }
        List<TermId> terms = assigned.stream().map(row -> TermId.of(row.termId)).toList();
        assigned.forEach(rows::delete);
        rows.flush();
        counts.recount(site, terms);
    }
}
