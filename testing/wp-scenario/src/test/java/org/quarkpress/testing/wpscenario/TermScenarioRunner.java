package org.quarkpress.testing.wpscenario;

import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.quarkpress.core.OptionStore;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TermId;
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.persistence.runtime.TenantIds;
import org.quarkpress.posts.PostException;
import org.quarkpress.taxonomy.TermChanges;
import org.quarkpress.taxonomy.TermException;
import org.quarkpress.taxonomy.TermOrder;
import org.quarkpress.taxonomy.TermQuery;
import org.quarkpress.taxonomy.TermRef;
import org.quarkpress.taxonomy.TermSnapshot;
import org.quarkpress.taxonomy.Terms;
import org.quarkpress.taxonomy.runtime.TermEntity;
import org.quarkpress.taxonomy.runtime.TermRepository;

/**
 * Plays the term operations of a scenario on QuarkPress's main site and records what {@link TermOutcomes} records for
 * WordPress. Terms are named as the driver names them: {@code t<k>} by creation order after the scenario's start, the
 * default terms by their options. Not thread-safe: one runner per scenario run.
 */
final class TermScenarioRunner {

    static final SiteId MAIN = ScenarioParityBase.MAIN;

    private final Terms terms;
    private final TermRepository rows;
    private final SiteScope scope;
    private final OptionStore store;
    private final PostScenarioRunner posts;
    private final List<Long> created = new ArrayList<>();
    private final Map<String, TermSnapshot> defaultsBefore = new LinkedHashMap<>();
    private long baseline;
    private List<UserId> users = List.of();

    TermScenarioRunner(Terms terms, TermRepository rows, SiteScope scope, OptionStore store, PostScenarioRunner posts) {
        this.terms = terms;
        this.rows = rows;
        this.scope = scope;
        this.store = store;
        this.posts = posts;
    }

    /** Before the first operation: the newest term id so far, and the default terms as they are. */
    void start() throws Exception {
        baseline = scope.call(
                MAIN,
                () -> rows.getEntityManager()
                        .createQuery(
                                "select coalesce(max(t.id), 0) from TermEntity t where t.siteId = :site", Long.class)
                        .setParameter("site", TenantIds.param(MAIN))
                        .getSingleResult());
        for (Map.Entry<String, Long> entry : defaultIds().entrySet()) {
            Optional<TermSnapshot> term = posts.as(0, List.of(), () -> terms.find(TermId.of(entry.getValue())));
            term.ifPresent(found -> defaultsBefore.put(entry.getKey(), found));
        }
    }

    void run(int op, ScenarioOp.TermOp step, Outcome outcome, List<UserId> users) throws Exception {
        this.users = users;
        String kind = step.kind();
        try {
            runOne(op, step, outcome, kind);
        } catch (TermException refused) {
            outcome.done(op, kind, "refused:" + refused.code());
        } catch (PostException refused) {
            // an assignment to a post deleted earlier (difference D8): scenarios do not make one
            outcome.done(op, kind, "refused:" + refused.code());
        } catch (ForbiddenException | UnauthorizedException refused) {
            outcome.done(op, kind, refusedAnswer(step));
        } catch (MissingReference missing) {
            outcome.done(op, kind, "missing");
        }
        track();
    }

    private void runOne(int op, ScenarioOp.TermOp step, Outcome outcome, String kind) throws Exception {
        switch (step) {
            case ScenarioOp.InsertTerm insert -> {
                TermChanges changes = changes(insert.fields(), insert.taxonomy());
                TermSnapshot term = posts.as(insert.as(), users, () -> terms.create(insert.taxonomy(), changes));
                track();
                outcome.termCreated(op, ref(term.id().value()));
            }
            case ScenarioOp.UpdateTerm update -> {
                TermId id = id(update.term(), update.taxonomy());
                TermChanges changes = changes(update.fields(), update.taxonomy());
                posts.as(update.as(), users, () -> terms.update(id, update.taxonomy(), changes));
                outcome.done(op, kind, "ok");
            }
            case ScenarioOp.DeleteTerm delete -> {
                TermId id = id(delete.term(), delete.taxonomy());
                Optional<TermSnapshot> before = posts.as(delete.as(), users, () -> terms.delete(id, delete.taxonomy()));
                outcome.done(op, kind, before.isPresent() ? "deleted" : "nothing");
            }
            case ScenarioOp.SetPostTerms set -> {
                PostId post = post(set.post());
                List<TermRef> refs = items(set.terms(), set.taxonomy());
                List<TermId> ids =
                        posts.as(set.as(), users, () -> terms.setPostTerms(post, set.taxonomy(), refs, set.append()));
                track();
                outcome.termRefs(
                        op, kind, ids.stream().map(id -> ref(id.value())).toList());
            }
            case ScenarioOp.RemovePostTerms remove -> {
                PostId post = post(remove.post());
                List<TermRef> refs = items(remove.terms(), remove.taxonomy());
                boolean removed =
                        posts.as(remove.as(), users, () -> terms.removePostTerms(post, remove.taxonomy(), refs));
                outcome.done(op, kind, "removed=" + removed);
            }
            case ScenarioOp.PostTerms read -> {
                PostId post = post(read.post());
                List<TermSnapshot> found = posts.as(0, users, () -> terms.postTerms(post, read.taxonomy()));
                outcome.termRefs(op, kind, counted(found));
            }
            case ScenarioOp.QueryTerms query -> {
                TermQuery built = query(query.args());
                List<TermSnapshot> found = posts.as(query.as(), users, () -> terms.query(built));
                // Every count is recorded, padded or not, as the driver records it.
                List<String> refs = counted(found);
                outcome.termRefs(op, kind, query.args().unordered() ? TermRecords.sortedRefs(refs) : refs);
            }
            case ScenarioOp.GetTerm get -> {
                String taxonomy = get.taxonomy().isEmpty() ? "category" : get.taxonomy();
                TermId id = id(get.term(), taxonomy);
                Optional<TermSnapshot> found = posts.as(
                        0, users, () -> get.taxonomy().isEmpty() ? terms.find(id) : terms.find(id, get.taxonomy()));
                if (found.isEmpty()) {
                    outcome.done(op, kind, "none");
                } else {
                    outcome.observedTerm("op" + op + ":get_term", observed(found.get()));
                }
            }
            case ScenarioOp.TermExists exists -> {
                OptionalLong parent = exists.parent().isPresent()
                        ? OptionalLong.of(parentId(exists.parent().get(), exists.taxonomy()))
                        : OptionalLong.empty();
                Optional<TermSnapshot> found =
                        posts.as(0, users, () -> terms.exists(exists.taxonomy(), exists.value(), parent));
                outcome.done(op, kind, found.map(term -> ref(term.id().value())).orElse("none"));
            }
        }
    }

    /**
     * What WordPress answers where QuarkPress's REST-like checks refuse: deleting a taxonomy's default term is refused
     * for everyone here (difference D1), where WordPress's API keeps the term and answers 0; any other refusal is
     * recorded as it is.
     */
    private String refusedAnswer(ScenarioOp.TermOp step) {
        if (step instanceof ScenarioOp.DeleteTerm delete && delete.term().equals("default")) {
            return "kept";
        }
        return "refused:forbidden";
    }

    /** Every term created on the main site after the start, in creation order; deleted ones stay listed. */
    void track() throws Exception {
        List<Long> ids = scope.call(
                MAIN,
                () -> rows.getEntityManager()
                        .createQuery(
                                "select t.id from TermEntity t where t.siteId = :site and t.id > :baseline order by t.id",
                                Long.class)
                        .setParameter("site", TenantIds.param(MAIN))
                        .setParameter("baseline", baseline)
                        .getResultList());
        for (Long id : ids) {
            if (!created.contains(id)) {
                created.add(id);
            }
        }
    }

    /** The final records of the created terms and of the default terms, as the driver reports them. */
    void record(Outcome outcome) throws Exception {
        track();
        for (int i = 0; i < created.size(); i++) {
            Optional<TermSnapshot> term = stored(created.get(i));
            outcome.observedTerm("term#t" + (i + 1), term.map(this::observed).orElse(ObservedTerm.DELETED));
        }
        for (Map.Entry<String, Long> entry : defaultIds().entrySet()) {
            Optional<TermSnapshot> term = stored(entry.getValue());
            outcome.observedTerm(
                    "term#default-" + entry.getKey(), term.map(this::observed).orElse(ObservedTerm.DELETED));
        }
    }

    /** Deletes the terms this run created (latest first) and gives the default terms back their fields. */
    void cleanUp() throws Exception {
        track();
        for (int i = created.size() - 1; i >= 0; i--) {
            Optional<TermSnapshot> term = stored(created.get(i));
            if (term.isPresent()) {
                posts.as(
                        0,
                        List.of(),
                        () -> terms.delete(term.get().id(), term.get().taxonomy()));
            }
        }
        for (TermSnapshot before : defaultsBefore.values()) {
            Optional<TermSnapshot> now = stored(before.id().value());
            if (now.isPresent()
                    && (!now.get().name().equals(before.name())
                            || !now.get().slug().equals(before.slug())
                            || !now.get().description().equals(before.description())
                            || !now.get().parent().equals(before.parent()))) {
                posts.as(
                        0,
                        List.of(),
                        () -> terms.update(
                                before.id(),
                                before.taxonomy(),
                                TermChanges.builder()
                                        .name(before.name())
                                        .slug(before.slug())
                                        .description(before.description())
                                        .parent(before.parent()
                                                .map(TermId::value)
                                                .orElse(0L))
                                        .build()));
            }
        }
    }

    /** The stored term, read in a scope of the main site (which has a transaction of its own). */
    private Optional<TermSnapshot> stored(long id) throws Exception {
        return scope.call(MAIN, () -> rows.findTerm(MAIN, TermId.of(id)).map(TermEntity::toSnapshot));
    }

    /** The ids of the default terms, by taxonomy (category, genre). */
    private Map<String, Long> defaultIds() throws Exception {
        Map<String, Long> ids = new LinkedHashMap<>();
        ids.put("category", option("default_category"));
        ids.put("genre", option("default_term_genre"));
        ids.values().removeIf(id -> id <= 0);
        return ids;
    }

    private long option(String name) throws Exception {
        return scope.call(
                MAIN,
                () -> store.find(MAIN, name)
                        .map(value -> value.isNumber() ? value.asLong() : Long.parseLong(value.asText()))
                        .orElse(0L));
    }

    /** Terms as {@code <ref>=<count>}, in the order given, as the driver records post_terms and query_terms. */
    private List<String> counted(List<TermSnapshot> found) {
        return found.stream()
                .map(term -> ref(term.id().value()) + "=" + term.count())
                .toList();
    }

    /** The default terms' names by id ({@code default-<taxonomy>}), as the driver names them. */
    private Map<Long, String> defaultsById() {
        try {
            Map<Long, String> defaults = new HashMap<>();
            defaultIds().forEach((taxonomy, value) -> defaults.put(value, "default-" + taxonomy));
            return defaults;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String ref(long id) {
        return TermRecords.termRef(id, created, defaultsById());
    }

    private ObservedTerm observed(TermSnapshot term) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", term.id().value());
        raw.put("taxonomy", term.taxonomy());
        raw.put("name", term.name());
        raw.put("slug", term.slug());
        raw.put("description", term.description());
        raw.put("parent", term.parent().map(TermId::value).orElse(0L));
        raw.put("count", (long) term.count());
        return TermRecords.record(raw, created, defaultsById());
    }

    /** A reference that names nothing (yet): the operation is recorded as missing, as the driver records it. */
    private static final class MissingReference extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private TermId id(String ref, String taxonomy) throws Exception {
        if (ref.equals("default")) {
            Long id = defaultIds().get(taxonomy);
            if (id == null) {
                throw new MissingReference();
            }
            return TermId.of(id);
        }
        int index = ScenarioRules.termIndex(ref);
        if (index < 1 || index > created.size()) {
            throw new MissingReference();
        }
        return TermId.of(created.get(index - 1));
    }

    private long parentId(String ref, String taxonomy) throws Exception {
        return ref.equals("none") ? 0 : id(ref, taxonomy).value();
    }

    private PostId post(int index) {
        return posts.post(index).orElseThrow(MissingReference::new);
    }

    private TermChanges changes(TermFields fields, String taxonomy) throws Exception {
        TermChanges.Builder changes = TermChanges.builder();
        fields.name().ifPresent(changes::name);
        fields.slug().ifPresent(changes::slug);
        fields.description().ifPresent(changes::description);
        if (fields.parent().isPresent()) {
            changes.parent(parentId(fields.parent().get(), taxonomy));
        }
        return changes.build();
    }

    private List<TermRef> items(List<TermItem> items, String taxonomy) throws Exception {
        List<TermRef> refs = new ArrayList<>();
        for (TermItem item : items) {
            refs.add(
                    item.term().isPresent()
                            ? TermRef.id(id(item.term().get(), taxonomy))
                            : TermRef.name(item.name().get()));
        }
        return refs;
    }

    private TermQuery query(TermQueryArgs args) throws Exception {
        String taxonomy =
                args.taxonomies().isEmpty() ? "category" : args.taxonomies().get(0);
        TermQuery.Builder query =
                TermQuery.builder().taxonomies(args.taxonomies().toArray(String[]::new));
        args.orderBy().ifPresent(text -> query.orderBy(TermOrder.parse(text)));
        args.order().ifPresent(query::order);
        args.hideEmpty().ifPresent(query::hideEmpty);
        args.hierarchical().ifPresent(query::hierarchical);
        query.padCounts(args.padCounts()).all(args.all()).childless(args.childless());
        query.include(ids(args.include(), taxonomy))
                .exclude(ids(args.exclude(), taxonomy))
                .excludeTree(ids(args.excludeTree(), taxonomy));
        query.slugs(args.slugs().toArray(String[]::new)).names(args.names().toArray(String[]::new));
        args.search().ifPresent(query::search);
        args.nameLike().ifPresent(query::nameLike);
        args.descriptionLike().ifPresent(query::descriptionLike);
        if (args.parent().isPresent()) {
            query.parent(parentId(args.parent().get(), taxonomy));
        }
        if (args.childOf().isPresent()) {
            query.childOf(id(args.childOf().get(), taxonomy).value());
        }
        List<PostId> named = new ArrayList<>();
        for (int index : args.posts()) {
            named.add(post(index));
        }
        query.posts(named.toArray(PostId[]::new));
        args.number().ifPresent(query::number);
        args.offset().ifPresent(query::offset);
        return query.build();
    }

    private TermId[] ids(List<String> refs, String taxonomy) throws Exception {
        List<TermId> ids = new ArrayList<>();
        for (String ref : refs) {
            ids.add(id(ref, taxonomy));
        }
        return ids.toArray(TermId[]::new);
    }
}
