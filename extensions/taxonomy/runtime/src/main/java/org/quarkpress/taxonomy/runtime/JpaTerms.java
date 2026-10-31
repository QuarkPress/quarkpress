package org.quarkpress.taxonomy.runtime;

import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.OptimisticLockException;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.core.TermId;
import org.quarkpress.persistence.runtime.TenantIds;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.runtime.Acting;
import org.quarkpress.posts.runtime.PostCapabilityFacts;
import org.quarkpress.posts.runtime.PostEntity;
import org.quarkpress.posts.runtime.PostLocks;
import org.quarkpress.posts.runtime.PostRepository;
import org.quarkpress.taxonomy.Taxonomies;
import org.quarkpress.taxonomy.TermChanges;
import org.quarkpress.taxonomy.TermException;
import org.quarkpress.taxonomy.TermField;
import org.quarkpress.taxonomy.TermQuery;
import org.quarkpress.taxonomy.TermRef;
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

    @Inject
    TermRepository terms;

    @Inject
    TermLookups lookups;

    @Inject
    TermCapabilityFacts facts;

    @Inject
    PostRepository posts;

    @Inject
    PostCapabilityFacts postFacts;

    @Inject
    TermAssignments assignments;

    @Inject
    TermSlugs slugs;

    @Inject
    TermQueries queries;

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

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public Optional<TermSnapshot> delete(TermId term, String taxonomy) {
        Objects.requireNonNull(term, "term");
        Objects.requireNonNull(taxonomy, "taxonomy");
        SiteId siteId = site.siteId();
        Optional<TaxonomyDefinition> definition = taxonomies.find(taxonomy);
        if (definition.isEmpty()) {
            return Optional.empty();
        }
        Optional<TermSnapshot> unlocked = lookups.find(siteId, term, taxonomy).map(TermEntity::toSnapshot);
        if (unlocked.isEmpty()) {
            return Optional.empty();
        }
        acting.require(siteId, "delete_term", Optional.of(facts.of(unlocked.get())));
        locks.lock(siteId);
        Optional<TermEntity> row = terms.lockTerm(siteId, term).filter(locked -> locked.taxonomy.equals(taxonomy));
        if (row.isEmpty()) {
            return Optional.empty();
        }
        acting.require(siteId, "delete_term", Optional.of(facts.of(row.get().toSnapshot())));
        return Optional.of(writes.delete(siteId, definition.get(), row.get()));
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

    /** One element of an assignment, resolved: an existing term, a name to create, or nothing. */
    private record Resolved(Optional<TermEntity> term, Optional<String> create) {}

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public List<TermId> setPostTerms(PostId post, String taxonomy, List<TermRef> refs, boolean append) {
        Objects.requireNonNull(post, "post");
        Objects.requireNonNull(taxonomy, "taxonomy");
        List<TermRef> given = List.copyOf(refs);
        SiteId siteId = site.siteId();
        TaxonomyDefinition definition = taxonomies.require(taxonomy);
        checkAssignment(siteId, definition, post, resolve(siteId, definition, given, true));
        locks.lock(siteId);
        checkAssignment(siteId, definition, post, resolve(siteId, definition, given, true));
        return writes.written(() -> {
            List<TermId> requested = new ArrayList<>();
            for (TermRef ref : given) {
                // resolved again one by one: a name may find the term an earlier name of this call created
                Resolved resolved = resolveOne(siteId, definition, ref, true);
                if (resolved.term().isPresent()) {
                    requested.add(TermId.of(resolved.term().get().id));
                } else if (resolved.create().isPresent()) {
                    requested.add(writes.insert(
                                    siteId,
                                    definition,
                                    TermChanges.builder()
                                            .name(resolved.create().get())
                                            .build())
                            .id());
                }
            }
            assignments.set(siteId, post, definition, requested, append);
            return List.copyOf(requested);
        });
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public boolean removePostTerms(PostId post, String taxonomy, List<TermRef> refs) {
        Objects.requireNonNull(post, "post");
        Objects.requireNonNull(taxonomy, "taxonomy");
        List<TermRef> given = List.copyOf(refs);
        SiteId siteId = site.siteId();
        TaxonomyDefinition definition = taxonomies.require(taxonomy);
        checkAssignment(siteId, definition, post, resolve(siteId, definition, given, false));
        locks.lock(siteId);
        List<Resolved> resolved = resolve(siteId, definition, given, false);
        checkAssignment(siteId, definition, post, resolved);
        List<TermId> named = resolved.stream()
                .flatMap(entry -> entry.term().stream())
                .map(term -> TermId.of(term.id))
                .toList();
        return writes.written(() -> assignments.remove(siteId, post, definition.key(), named));
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public List<TermSnapshot> postTerms(PostId post, String taxonomy) {
        Objects.requireNonNull(post, "post");
        Objects.requireNonNull(taxonomy, "taxonomy");
        SiteId siteId = site.siteId();
        TaxonomyDefinition definition = taxonomies.require(taxonomy);
        acting.requireReadable(siteId, postFacts.of(storedPost(siteId, post)));
        requireReadable(siteId, definition);
        return terms.getEntityManager()
                .createQuery(
                        "select t from TermEntity t, TermAssignmentEntity a where t.siteId = :site"
                                + " and a.siteId = :site and a.termId = t.id and a.postId = :post"
                                + " and t.taxonomy = :taxonomy order by " + lookups.nameOrder("t") + ", t.id",
                        TermEntity.class)
                .setParameter("site", TenantIds.param(siteId))
                .setParameter("post", post.value())
                .setParameter("taxonomy", definition.key())
                .getResultStream()
                .map(TermEntity::toSnapshot)
                .toList();
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public Optional<TermSnapshot> find(TermId term) {
        Objects.requireNonNull(term, "term");
        SiteId siteId = site.siteId();
        Optional<TermSnapshot> found = terms.findTerm(siteId, term).map(TermEntity::toSnapshot);
        if (found.isEmpty()) {
            return found;
        }
        Optional<TaxonomyDefinition> definition = taxonomies.find(found.get().taxonomy());
        if (definition.isEmpty()) {
            return Optional.empty();
        }
        requireReadable(siteId, definition.get());
        return found;
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public Optional<TermSnapshot> find(TermId term, String taxonomy) {
        Objects.requireNonNull(term, "term");
        Objects.requireNonNull(taxonomy, "taxonomy");
        SiteId siteId = site.siteId();
        TaxonomyDefinition definition = taxonomies.require(taxonomy);
        requireReadable(siteId, definition);
        return lookups.find(siteId, term, definition.key()).map(TermEntity::toSnapshot);
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public Optional<TermSnapshot> findBy(String taxonomy, TermField field, String value) {
        Objects.requireNonNull(taxonomy, "taxonomy");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(value, "value");
        SiteId siteId = site.siteId();
        Optional<TaxonomyDefinition> definition = taxonomies.find(taxonomy);
        if (definition.isEmpty() || value.isEmpty()) {
            return Optional.empty();
        }
        requireReadable(siteId, definition.get());
        String key = definition.get().key();
        Optional<TermEntity> found = switch (field) {
            case ID -> {
                long id = TermRules.phpInt(value);
                yield id > 0 ? lookups.find(siteId, TermId.of(id), key) : Optional.empty();
            }
            case SLUG -> lookups.bySlug(siteId, key, slugs.sanitize(siteId, key, value));
            case NAME -> lookups.byName(siteId, key, value);
        };
        return found.map(TermEntity::toSnapshot);
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public Optional<TermSnapshot> exists(String taxonomy, String value, OptionalLong parent) {
        Objects.requireNonNull(taxonomy, "taxonomy");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(parent, "parent");
        SiteId siteId = site.siteId();
        Optional<TaxonomyDefinition> definition = taxonomies.find(taxonomy);
        if (definition.isEmpty()) {
            return Optional.empty();
        }
        requireReadable(siteId, definition.get());
        return lookups.exists(siteId, definition.get().key(), value, parent).map(TermEntity::toSnapshot);
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public List<TermId> ancestors(TermId term, String taxonomy) {
        Objects.requireNonNull(term, "term");
        Objects.requireNonNull(taxonomy, "taxonomy");
        SiteId siteId = site.siteId();
        TaxonomyDefinition definition = taxonomies.require(taxonomy);
        requireReadable(siteId, definition);
        if (!definition.hierarchical()) {
            return List.of();
        }
        return TermHierarchy.ancestors(term, id -> lookups.storedParent(siteId, definition.key(), id));
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public List<TermId> children(TermId term, String taxonomy) {
        Objects.requireNonNull(term, "term");
        Objects.requireNonNull(taxonomy, "taxonomy");
        SiteId siteId = site.siteId();
        TaxonomyDefinition definition = taxonomies.require(taxonomy);
        requireReadable(siteId, definition);
        return TermHierarchy.children(term, lookups.children(siteId, definition));
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public List<TermSnapshot> query(TermQuery query) {
        Objects.requireNonNull(query, "query");
        SiteId siteId = site.siteId();
        List<TaxonomyDefinition> queried = queried(siteId, query);
        requireReadablePosts(siteId, query);
        return queries.run(siteId, queried, !query.taxonomies().isEmpty(), query);
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public long count(TermQuery query) {
        Objects.requireNonNull(query, "query");
        SiteId siteId = site.siteId();
        List<TaxonomyDefinition> queried = queried(siteId, query);
        requireReadablePosts(siteId, query);
        return queries.count(siteId, queried, !query.taxonomies().isEmpty(), query);
    }

    /**
     * The named taxonomies, each registered and readable; without names, every registered one the caller may read.
     */
    private List<TaxonomyDefinition> queried(SiteId siteId, TermQuery query) {
        if (query.taxonomies().isEmpty()) {
            return taxonomies.all().stream()
                    .filter(taxonomy -> canRead(siteId, taxonomy))
                    .toList();
        }
        List<TaxonomyDefinition> named = new ArrayList<>();
        for (String key : query.taxonomies()) {
            TaxonomyDefinition taxonomy = taxonomies.require(key);
            requireReadable(siteId, taxonomy);
            named.add(taxonomy);
        }
        return named;
    }

    /**
     * Each post a query names exists on this site ({@code invalid_post}) and is readable by the caller, as WordPress's
     * REST layer requires before it lists a post's terms.
     */
    private void requireReadablePosts(SiteId siteId, TermQuery query) {
        for (PostId post : query.posts()) {
            acting.requireReadable(siteId, postFacts.of(storedPost(siteId, post)));
        }
    }

    /**
     * Whether the caller may read the taxonomy's terms: it is publicly queryable, or shown in REST (whose routes
     * WordPress serves to anyone), or the caller may assign them.
     */
    boolean canRead(SiteId siteId, TaxonomyDefinition taxonomy) {
        return taxonomy.publiclyQueryable()
                || taxonomy.showInRest()
                || acting.can(siteId, taxonomy.capability("assign_terms"), Optional.empty());
    }

    /** A taxonomy neither publicly queryable nor shown in REST is read only by callers that may assign its terms. */
    void requireReadable(SiteId siteId, TaxonomyDefinition taxonomy) {
        if (!canRead(siteId, taxonomy)) {
            acting.require(siteId, taxonomy.capability("assign_terms"), Optional.empty());
        }
    }

    /** The stored post of this site; a missing post, or one of another site, is {@code invalid_post}. */
    private PostSnapshot storedPost(SiteId siteId, PostId post) {
        return posts.findPost(siteId, post)
                .map(PostEntity::toSnapshot)
                .orElseThrow(() -> PostException.invalidPost(post));
    }

    private List<Resolved> resolve(SiteId siteId, TaxonomyDefinition taxonomy, List<TermRef> refs, boolean create) {
        List<Resolved> resolved = new ArrayList<>();
        for (TermRef ref : refs) {
            resolved.add(resolveOne(siteId, taxonomy, ref, create));
        }
        return resolved;
    }

    /**
     * An id is a term of the taxonomy on this site or nothing; a name is found as a slug then as a name, or, when
     * {@code create} is set, is a name to create (a blank name is nothing).
     */
    private Resolved resolveOne(SiteId siteId, TaxonomyDefinition taxonomy, TermRef ref, boolean create) {
        return switch (ref) {
            case TermRef.Id id -> new Resolved(lookups.find(siteId, id.term(), taxonomy.key()), Optional.empty());
            case TermRef.Name name -> {
                if (TermText.blank(name.name())) {
                    yield new Resolved(Optional.empty(), Optional.empty());
                }
                Optional<TermEntity> found = lookups.exists(siteId, taxonomy.key(), name.name(), OptionalLong.empty());
                yield new Resolved(found, found.isEmpty() && create ? Optional.of(name.name()) : Optional.empty());
            }
        };
    }

    /**
     * The checks of an assignment, all before anything is written: the post exists on this site and the caller may
     * edit it; the caller may assign each existing term; a name to create needs the creating capability and must not
     * sanitize to nothing.
     */
    private void checkAssignment(SiteId siteId, TaxonomyDefinition taxonomy, PostId post, List<Resolved> resolved) {
        acting.require(siteId, "edit_post", Optional.of(postFacts.of(storedPost(siteId, post))));
        boolean creates = false;
        for (Resolved entry : resolved) {
            if (entry.term().isPresent()) {
                acting.require(
                        siteId,
                        "assign_term",
                        Optional.of(facts.of(entry.term().get().toSnapshot())));
            }
            if (entry.create().isPresent()) {
                creates = true;
                if (TermText.name(entry.create().get()).isEmpty()) {
                    throw TermException.invalidName();
                }
            }
        }
        if (creates) {
            acting.require(siteId, createCapability(taxonomy), Optional.empty());
        }
    }
}
