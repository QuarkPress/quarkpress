package org.quarkpress.taxonomy.runtime;

import io.quarkus.arc.All;
import io.quarkus.arc.InstanceHandle;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Supplier;
import org.quarkpress.core.Filters;
import org.quarkpress.core.PostId;
import org.quarkpress.core.QuarkPressClock;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.core.TermId;
import org.quarkpress.persistence.runtime.TenantIds;
import org.quarkpress.posts.runtime.Acting;
import org.quarkpress.taxonomy.TermChanges;
import org.quarkpress.taxonomy.TermDraft;
import org.quarkpress.taxonomy.TermException;
import org.quarkpress.taxonomy.TermSnapshot;
import org.quarkpress.taxonomy.TermWriteContext;
import org.quarkpress.taxonomy.TermWriteFilter;

/**
 * WordPress's term saves and deletes, without capability checks (the service checks before it calls them): the caller
 * holds the site's post lock. Input refusals come before the first write; once a call writes, any failure marks the
 * transaction for rollback.
 */
@ApplicationScoped
public class TermWrites {

    @Inject
    TermRepository terms;

    @Inject
    TermLookups lookups;

    @Inject
    TermSlugs slugs;

    @Inject
    TermEvents events;

    @Inject
    Acting acting;

    @Inject
    QuarkPressClock clock;

    @Inject
    @All
    List<InstanceHandle<TermWriteFilter>> writeFilters;

    @Inject
    TransactionSynchronizationRegistry registry;

    @Inject
    TermAssignments assignments;

    @Inject
    TermAssignmentRepository assignmentRows;

    @Inject
    DefaultTerms defaults;

    /** Inserts a term: WordPress's {@code wp_insert_term}. */
    public TermSnapshot insert(SiteId site, TaxonomyDefinition taxonomy, TermChanges changes) {
        Instant now = clock.now().truncatedTo(ChronoUnit.MICROS);
        Optional<TermId> parent = requireParent(site, taxonomy, changes.parent());
        String raw = changes.name().orElse("");
        if (TermText.blank(raw)) {
            throw TermException.emptyName();
        }
        String name = TermText.name(raw);
        if (name.isEmpty()) {
            throw TermException.invalidName();
        }
        String description = TermText.description(changes.description().orElse(""));
        String given = slugs.sanitize(site, taxonomy.key(), changes.slug().orElse(""));
        boolean provided = !TermRules.phpEmpty(given);
        String slug = provided ? given : slugs.generate(site, taxonomy.key(), name);
        Optional<TermId> duplicate = DuplicateNames.conflict(
                name,
                slug,
                provided,
                taxonomy.hierarchical(),
                parent,
                lookups.sameLength(site, taxonomy.key(), parent, name),
                lookups.bySlug(site, taxonomy.key(), slug).map(TermWrites::stored));
        if (duplicate.isPresent()) {
            throw TermException.exists(duplicate.get(), taxonomy.hierarchical());
        }
        slug = UniqueTermSlugs.unique(
                slug, taxonomy.hierarchical(), parent, Optional.empty(), lookups.uniqueLookup(site, taxonomy.key()));
        TermDraft draft = filtered(
                site,
                taxonomy,
                new TermDraft(name, slug, description, parent),
                new TermWriteContext(site, taxonomy.key(), Optional.empty(), acting.user(), false));
        return written(() -> {
            TermEntity row = TermEntity.create(site, taxonomy.key());
            apply(row, draft);
            terms.persist(row);
            terms.flush();
            if (row.slug.isEmpty()) {
                row.slug = idSlug(site, taxonomy, row);
                terms.flush();
            }
            TermSnapshot after = row.toSnapshot();
            events.created(site, now, after);
            return after;
        });
    }

    /**
     * Updates the locked term: WordPress's {@code wp_update_term}. Every refusal comes before the first write; the
     * loop members a new parent cuts are written with the term, so a loop further up is cut only when the update
     * succeeds, and a refused update leaves it as it was. Each member of the loop only loses its parent.
     */
    public TermSnapshot update(SiteId site, TaxonomyDefinition taxonomy, TermEntity row, TermChanges changes) {
        Instant now = clock.now().truncatedTo(ChronoUnit.MICROS);
        TermSnapshot before = row.toSnapshot();
        Optional<TermId> requested =
                changes.parent().isPresent() ? requireParent(site, taxonomy, changes.parent()) : before.parent();
        String name = TermText.name(changes.name().orElse(row.name));
        if (TermText.blank(name)) {
            throw TermException.emptyName();
        }
        String description = TermText.description(changes.description().orElse(row.description));
        TermHierarchy.Decision hierarchy =
                TermHierarchy.check(before.id(), requested, id -> lookups.storedParent(site, taxonomy.key(), id));
        String sanitized = slugs.sanitize(site, taxonomy.key(), changes.slug().orElse(row.slug));
        boolean regenerated = TermRules.phpEmpty(sanitized);
        String slug = regenerated ? slugs.generate(site, taxonomy.key(), name) : sanitized;
        Optional<TermEntity> holder =
                lookups.bySlug(site, taxonomy.key(), slug).filter(other -> !other.id.equals(row.id));
        if (holder.isPresent()) {
            if (regenerated || !hierarchy.parent().equals(before.parent())) {
                slug = UniqueTermSlugs.unique(
                        slug,
                        taxonomy.hierarchical(),
                        requested,
                        Optional.of(before.id()),
                        lookups.uniqueLookup(site, taxonomy.key()));
            } else {
                throw TermException.duplicateSlug(slug);
            }
        }
        TermDraft draft = filtered(
                site,
                taxonomy,
                new TermDraft(name, slug, description, hierarchy.parent()),
                new TermWriteContext(site, taxonomy.key(), Optional.of(before), acting.user(), true));
        return written(() -> {
            cutLoop(site, now, hierarchy.cut());
            apply(row, draft);
            terms.flush();
            if (row.slug.isEmpty()) {
                row.slug = idSlug(site, taxonomy, row);
                terms.flush();
            }
            TermSnapshot after = row.toSnapshot();
            events.updated(site, now, before, after);
            return after;
        });
    }

    /**
     * Deletes the locked term: WordPress's {@code wp_delete_term} after its checks. Children are handed on, posts
     * reassigned (counts and announcements follow), the row removed, and the delete announced. Any failure marks the
     * transaction for rollback, so a failed delete changes nothing.
     */
    public TermSnapshot delete(SiteId site, TaxonomyDefinition taxonomy, TermEntity row) {
        Instant now = clock.now().truncatedTo(ChronoUnit.MICROS);
        TermSnapshot before = row.toSnapshot();
        Optional<TermId> replacement = replacement(site, taxonomy, before.id());
        return written(() -> {
            releaseChildren(site, taxonomy, before);
            for (PostId post : assignmentRows.postsOf(site, before.id())) {
                List<TermId> held = assignments.termsOf(site, post, taxonomy.key());
                List<TermId> next = held.size() == 1 && replacement.isPresent()
                        ? List.of(replacement.get())
                        : held.stream().filter(id -> !id.equals(before.id())).toList();
                assignments.set(site, post, taxonomy, next, false);
            }
            terms.delete(row);
            terms.flush();
            events.deleted(site, now, before);
            return before;
        });
    }

    /**
     * The term that posts left without a term of the taxonomy get: its current default term, unless that is the term
     * being deleted. The caller is bound to {@code site}.
     */
    public Optional<TermId> replacement(SiteId site, TaxonomyDefinition taxonomy, TermId deleted) {
        return defaults.current(site, taxonomy).filter(id -> !id.equals(deleted));
    }

    /**
     * The children of a term being deleted, locked in id order: in a hierarchical taxonomy those of its taxonomy get its
     * parent; children of a flat term, and children of other taxonomies, are left without parent (the cascading key
     * would otherwise delete them). Only the parent changes, not their version. The site condition is defence in depth:
     * the composite parent key keeps children on their parent's site.
     */
    private void releaseChildren(SiteId site, TaxonomyDefinition taxonomy, TermSnapshot term) {
        Object siteParam = TenantIds.param(site);
        List<TermEntity> children = terms.find(
                        "siteId = ?1 and parentId = ?2 and id <> ?2 order by id",
                        siteParam,
                        term.id().value())
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .list();
        if (children.isEmpty()) {
            return;
        }
        Long handedDown =
                taxonomy.hierarchical() ? term.parent().map(TermId::value).orElse(null) : null;
        List<Long> heirs = new ArrayList<>();
        List<Long> orphans = new ArrayList<>();
        for (TermEntity child : children) {
            (handedDown != null && child.taxonomy.equals(term.taxonomy()) ? heirs : orphans).add(child.id);
        }
        if (!orphans.isEmpty()) {
            terms.update("parentId = null where siteId = ?1 and id in ?2", siteParam, orphans);
        }
        if (!heirs.isEmpty()) {
            terms.update("parentId = ?1 where siteId = ?2 and id in ?3", handedDown, siteParam, heirs);
        }
        children.forEach(child -> terms.getEntityManager().refresh(child));
    }

    /** Leaves each member of a loop further up without parent, in WordPress's order, announcing each as updated. */
    private void cutLoop(SiteId site, Instant now, List<TermId> members) {
        for (TermId member : new ArrayList<>(members)) {
            Optional<TermEntity> locked = terms.lockTerm(site, member);
            if (locked.isEmpty() || locked.get().parentId == null) {
                continue;
            }
            TermSnapshot memberBefore = locked.get().toSnapshot();
            locked.get().parentId = null;
            terms.flush();
            events.updated(site, now, memberBefore, locked.get().toSnapshot());
        }
    }

    /**
     * The parent a write asks for, checked as WordPress's REST layer checks it: none for 0 or less; refused for a flat
     * taxonomy; it must be a term of the taxonomy on this site.
     */
    public Optional<TermId> requireParent(SiteId site, TaxonomyDefinition taxonomy, OptionalLong given) {
        Optional<TermId> parent = TermRules.parent(given);
        if (parent.isEmpty()) {
            return parent;
        }
        if (!taxonomy.hierarchical()) {
            throw TermException.notHierarchical();
        }
        if (lookups.find(site, parent.get(), taxonomy.key()).isEmpty()) {
            throw TermException.missingParent();
        }
        return parent;
    }

    /**
     * The draft after the {@link TermWriteFilter}s, checked again: a changed parent by the parent rules, a changed slug
     * against the taxonomy's other terms, and every value against its column.
     */
    public TermDraft filtered(SiteId site, TaxonomyDefinition taxonomy, TermDraft draft, TermWriteContext context) {
        TermDraft result =
                Filters.apply(Filters.ordered(writeFilters), draft, (filter, value) -> filter.apply(value, context));
        if (!result.parent().equals(draft.parent())) {
            requireParent(
                    site,
                    taxonomy,
                    OptionalLong.of(result.parent().map(TermId::value).orElse(0L)));
        }
        TermRules.requireStorable(result.name(), result.slug(), result.description(), context.update());
        if (!result.slug().equals(draft.slug())) {
            Optional<TermEntity> holder = lookups.bySlug(site, taxonomy.key(), result.slug());
            Optional<TermId> self = context.before().map(TermSnapshot::id);
            if (holder.isPresent()
                    && !self.map(id -> id.value() == holder.get().id).orElse(false)) {
                throw TermException.duplicateSlug(result.slug());
            }
        }
        return result;
    }

    /**
     * The slug of a term whose name gives none: its id, as WordPress sets it, made unique when another term of the
     * taxonomy already holds that text.
     */
    String idSlug(SiteId site, TaxonomyDefinition taxonomy, TermEntity row) {
        return UniqueTermSlugs.unique(
                Long.toString(row.id),
                taxonomy.hierarchical(),
                Optional.ofNullable(row.parentId).map(TermId::of),
                Optional.of(TermId.of(row.id)),
                lookups.uniqueLookup(site, taxonomy.key()));
    }

    static void apply(TermEntity row, TermDraft draft) {
        row.name = draft.name();
        row.slug = draft.slug();
        row.description = draft.description();
        row.parentId = draft.parent().map(TermId::value).orElse(null);
    }

    static DuplicateNames.Stored stored(TermEntity term) {
        return new DuplicateNames.Stored(
                TermId.of(term.id),
                term.name,
                term.slug,
                Optional.ofNullable(term.parentId).map(TermId::of));
    }

    /**
     * Runs the part of a save that writes: an exception marks the transaction for rollback before it propagates, so a
     * failure after the first write never leaves a partly saved term for the caller to commit. The mark matters for
     * failures of the persistence calls (persist, flush, bulk updates) and of the helpers they run in; an observer that
     * throws has already marked the transaction when the event was published.
     */
    public <T> T written(Supplier<T> work) {
        try {
            return work.get();
        } catch (RuntimeException failure) {
            markRollbackOnly(failure);
            throw failure;
        }
    }

    /** Marks the transaction for rollback; a failure to do so is added to {@code failure} as suppressed. */
    public void markRollbackOnly(RuntimeException failure) {
        try {
            registry.setRollbackOnly();
        } catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }
}
