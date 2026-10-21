package org.quarkpress.taxonomy.runtime;

import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.OptimisticLockException;
import jakarta.transaction.Transactional;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.core.TermId;
import org.quarkpress.posts.runtime.Acting;
import org.quarkpress.posts.runtime.PostLocks;
import org.quarkpress.taxonomy.Taxonomies;
import org.quarkpress.taxonomy.TermChanges;
import org.quarkpress.taxonomy.TermException;
import org.quarkpress.taxonomy.TermSnapshot;
import org.quarkpress.taxonomy.Terms;

/**
 * The terms service: capability checks first (WordPress's REST rules), then the site's post lock, then the write
 * path. A refused capability check or an input refusal raised before the first write does not mark the caller's
 * transaction for rollback; a refused lock does.
 */
@ApplicationScoped
public class JpaTerms implements Terms {

    @Inject
    SiteContext site;

    @Inject
    Taxonomies taxonomies;

    @Inject
    Acting acting;

    @Inject
    PostLocks locks;

    @Inject
    TermWrites writes;

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public TermSnapshot create(String taxonomy, TermChanges changes) {
        Objects.requireNonNull(taxonomy, "taxonomy");
        Objects.requireNonNull(changes, "changes");
        SiteId siteId = site.siteId();
        TaxonomyDefinition definition = taxonomies.require(taxonomy);
        acting.require(siteId, createCapability(definition), Optional.empty());
        locks.lock(siteId);
        return writes.insert(siteId, definition, changes);
    }

    @Inject
    TermRepository terms;

    @Inject
    TermLookups lookups;

    @Inject
    TermCapabilityFacts facts;

    @Override
    @Transactional(
            dontRollbackOn = {
                IllegalArgumentException.class,
                OptimisticLockException.class,
                ForbiddenException.class,
                UnauthorizedException.class
            })
    public TermSnapshot update(TermId term, String taxonomy, TermChanges changes) {
        Objects.requireNonNull(term, "term");
        Objects.requireNonNull(taxonomy, "taxonomy");
        Objects.requireNonNull(changes, "changes");
        SiteId siteId = site.siteId();
        TaxonomyDefinition definition = taxonomies.require(taxonomy);
        TermEntity row = lockedRow(
                siteId, term, definition, stored -> acting.require(siteId, "edit_term", Optional.of(facts.of(stored))));
        if (changes.expectedVersion().isPresent() && changes.expectedVersion().getAsInt() != row.version) {
            throw new OptimisticLockException("term " + term.value() + " changed since version "
                    + changes.expectedVersion().getAsInt() + "; it is now at version " + row.version);
        }
        return writes.update(siteId, definition, row, changes);
    }

    /**
     * The stored term of the taxonomy, locked for the write: read and checked without locks (a missing term, or one of
     * another site or taxonomy, is {@code invalid_term}), then the site's post lock is taken and the term is read again
     * under its row lock and checked again, since it may have changed meanwhile.
     */
    TermEntity lockedRow(SiteId siteId, TermId term, TaxonomyDefinition taxonomy, Consumer<TermSnapshot> checks) {
        TermSnapshot unlocked = lookups.find(siteId, term, taxonomy.key())
                .map(TermEntity::toSnapshot)
                .orElseThrow(TermException::invalidTerm);
        checks.accept(unlocked);
        locks.lock(siteId);
        TermEntity row = terms.lockTerm(siteId, term)
                .filter(locked -> locked.taxonomy.equals(taxonomy.key()))
                .orElseThrow(TermException::invalidTerm);
        checks.accept(row.toSnapshot());
        return row;
    }

    /** Creating a term needs the taxonomy's edit_terms when it is hierarchical, its assign_terms when it is flat. */
    static String createCapability(TaxonomyDefinition taxonomy) {
        return taxonomy.capability(taxonomy.hierarchical() ? "edit_terms" : "assign_terms");
    }
}
