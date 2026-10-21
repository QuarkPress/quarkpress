package org.quarkpress.taxonomy.runtime;

import io.quarkus.arc.All;
import io.quarkus.arc.InstanceHandle;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Supplier;
import org.quarkpress.core.Filters;
import org.quarkpress.core.QuarkPressClock;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.core.TermId;
import org.quarkpress.posts.runtime.Acting;
import org.quarkpress.taxonomy.TermChanges;
import org.quarkpress.taxonomy.TermDraft;
import org.quarkpress.taxonomy.TermException;
import org.quarkpress.taxonomy.TermSnapshot;
import org.quarkpress.taxonomy.TermWriteContext;
import org.quarkpress.taxonomy.TermWriteFilter;

/**
 * WordPress's term saves, without capability checks (the service checks before it calls them): the caller holds the
 * site's post lock. Input refusals come before the first write; once a call writes, any failure marks the transaction
 * for rollback.
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
        if (!result.slug().equals(draft.slug())) {
            Optional<TermEntity> holder = lookups.bySlug(site, taxonomy.key(), result.slug());
            Optional<TermId> self = context.before().map(TermSnapshot::id);
            if (holder.isPresent()
                    && !self.map(id -> id.value() == holder.get().id).orElse(false)) {
                throw TermException.duplicateSlug(result.slug());
            }
        }
        TermRules.requireStorable(result.name(), result.slug(), result.description(), context.update());
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
     * Runs the part of a save that writes: any exception marks the transaction for rollback before it propagates, so a
     * refusal after the first write never leaves a partly saved term for the caller to commit.
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
