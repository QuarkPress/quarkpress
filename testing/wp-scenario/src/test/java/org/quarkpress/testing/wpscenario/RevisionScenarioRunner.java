package org.quarkpress.testing.wpscenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.quarkpress.core.PostId;
import org.quarkpress.core.RevisionId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.persistence.runtime.TenantIds;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostMeta;
import org.quarkpress.revisions.AutosaveChanges;
import org.quarkpress.revisions.AutosaveResult;
import org.quarkpress.revisions.RevisionCount;
import org.quarkpress.revisions.RevisionException;
import org.quarkpress.revisions.RevisionField;
import org.quarkpress.revisions.RevisionQuery;
import org.quarkpress.revisions.RevisionSnapshot;
import org.quarkpress.revisions.Revisions;
import org.quarkpress.revisions.runtime.RevisionEntity;
import org.quarkpress.revisions.runtime.RevisionRepository;

/**
 * Plays the revision operations and {@code update_post_meta} of a scenario on QuarkPress's main site and records what
 * {@link RevisionOutcomes} records for WordPress. Revisions are named as the driver names them: {@code r<k>} by
 * creation order of the revision rows after the scenario's start (deleted ones still counted; an autosave update
 * creates no row). Not thread-safe: one runner per scenario run.
 */
final class RevisionScenarioRunner {

    static final SiteId MAIN = ScenarioParityBase.MAIN;

    private final Revisions revisions;
    private final RevisionRepository rows;
    private final PostMeta meta;
    private final SiteScope scope;
    private final PostScenarioRunner posts;
    private final List<Long> created = new ArrayList<>();
    private final Instant started = Instant.now();
    private long baseline;
    private List<UserId> users = List.of();

    RevisionScenarioRunner(
            Revisions revisions, RevisionRepository rows, PostMeta meta, SiteScope scope, PostScenarioRunner posts) {
        this.revisions = revisions;
        this.rows = rows;
        this.meta = meta;
        this.scope = scope;
        this.posts = posts;
    }

    /** Before the first operation: the newest revision id so far, and the scenario's limit. */
    void start(OptionalInt revisionsKeep) throws Exception {
        baseline = scope.call(
                MAIN,
                () -> rows.getEntityManager()
                        .createQuery(
                                "select coalesce(max(r.id), 0) from RevisionEntity r where r.siteId = :site",
                                Long.class)
                        .setParameter("site", TenantIds.param(MAIN))
                        .getSingleResult());
        TierBRevisionsKeep.OVERRIDE.set(revisionsKeep);
    }

    void run(int op, ScenarioOp.PostOp step, Outcome outcome, List<UserId> users) throws Exception {
        this.users = users;
        String kind = step.kind();
        try {
            runOne(op, step, outcome, kind);
        } catch (RevisionException refused) {
            outcome.done(op, kind, "refused:" + refused.code());
        } catch (PostException refused) {
            outcome.done(op, kind, "refused:" + refused.code());
        } catch (ForbiddenException | UnauthorizedException refused) {
            outcome.done(op, kind, "refused:forbidden");
        } catch (MissingReference missing) {
            outcome.done(op, kind, "missing");
        }
        track();
    }

    private void runOne(int op, ScenarioOp.PostOp step, Outcome outcome, String kind) throws Exception {
        switch (step) {
            case ScenarioOp.UpdatePostMeta update -> {
                PostId post = post(update.post());
                boolean changed = posts.as(
                        update.as(), users, () -> meta.update(post, update.key(), TextNode.valueOf(update.value())));
                outcome.done(op, kind, "changed=" + changed);
            }
            case ScenarioOp.Autosave autosave -> {
                PostId post = post(autosave.post());
                AutosaveChanges changes = changes(autosave);
                AutosaveResult result = posts.as(autosave.as(), users, () -> revisions.autosave(post, changes));
                track();
                outcome.done(
                        op,
                        kind,
                        switch (result) {
                            case AutosaveResult.Draft draft ->
                                PostRecords.postRef(draft.post().id().value(), posts.postIndex());
                            case AutosaveResult.Stored stored ->
                                ref(stored.autosave().id());
                        });
            }
            case ScenarioOp.Restore restore -> {
                RevisionId revision = revision(restore.revision());
                Set<RevisionField> fields = EnumSet.noneOf(RevisionField.class);
                restore.fields().forEach(field -> fields.add(RevisionField.valueOf(field.toUpperCase(Locale.ROOT))));
                try {
                    posts.as(restore.as(), users, () -> revisions.restore(revision, fields));
                    outcome.done(op, kind, "ok");
                } catch (RevisionException refused) {
                    if (!refused.code().equals("rest_post_invalid_id")) {
                        throw refused;
                    }
                    // the revision was deleted earlier: WordPress's function answers null
                    outcome.done(op, kind, Outcome.NOTHING);
                }
            }
            case ScenarioOp.DeleteRevision delete -> {
                RevisionId revision = revision(delete.revision());
                Optional<RevisionSnapshot> removed = posts.as(0, users, () -> revisions.delete(revision));
                outcome.done(op, kind, removed.isPresent() ? "deleted" : Outcome.NOTHING);
            }
            case ScenarioOp.ListRevisions list -> {
                PostId post = post(list.post());
                RevisionQuery query = query(list.args());
                List<RevisionSnapshot> found = posts.as(list.as(), users, () -> revisions.ofPost(post, query));
                outcome.revisionRefs(
                        op, kind, found.stream().map(row -> ref(row.id())).toList());
            }
            case ScenarioOp.AutosaveOf of -> {
                PostId post = post(of.post());
                Optional<UserId> user = of.user() == 0 ? Optional.empty() : Optional.of(users.get(of.user() - 1));
                Optional<RevisionSnapshot> found = posts.as(of.as(), users, () -> revisions.autosaveOf(post, user));
                outcome.done(op, kind, found.map(row -> ref(row.id())).orElse(Outcome.NONE));
            }
            case ScenarioOp.CountRevisions count -> {
                PostId post = post(count.post());
                RevisionCount found = posts.as(count.as(), users, () -> revisions.latestAndCount(post));
                outcome.done(
                        op,
                        kind,
                        "latest=" + found.latest().map(this::ref).orElse(Outcome.NONE) + " count=" + found.count());
            }
            default -> throw new IllegalStateException("not a revision operation: " + step);
        }
    }

    private AutosaveChanges changes(ScenarioOp.Autosave autosave) {
        AutosaveChanges.Builder changes = AutosaveChanges.builder();
        autosave.fields().title().ifPresent(changes::title);
        autosave.fields().content().ifPresent(changes::content);
        autosave.fields().excerpt().ifPresent(changes::excerpt);
        autosave.meta().ifPresent(given -> {
            // An empty object still makes the meta part present on WordPress's side; here it is no meta at all.
            given.forEach((key, value) -> changes.meta(key, TextNode.valueOf(value)));
        });
        return changes.build();
    }

    private RevisionQuery query(RevisionListArgs args) {
        RevisionQuery.Builder query = RevisionQuery.builder();
        args.order().ifPresent(order -> query.ascending(order.equals("asc")));
        args.number().ifPresent(query::limit);
        args.offset().ifPresent(query::offset);
        args.author().ifPresent(author -> query.author(users.get(author - 1)));
        args.autosaves()
                .ifPresent(mode -> query.autosaves(RevisionQuery.Autosaves.valueOf(mode.toUpperCase(Locale.ROOT))));
        args.checkEnabled().ifPresent(query::checkEnabled);
        return query.build();
    }

    /** Every revision row created on the main site after the start, in creation order; deleted ones stay listed. */
    void track() throws Exception {
        List<Long> ids = scope.call(
                MAIN,
                () -> rows.getEntityManager()
                        .createQuery(
                                "select r.id from RevisionEntity r where r.siteId = :site and r.id > :baseline"
                                        + " order by r.id",
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

    /** The final records of the created revisions, as the driver reports {@code revisions}. */
    void record(Outcome outcome, List<UserId> users, UserId installer) throws Exception {
        track();
        Map<Long, Integer> userIndex = new HashMap<>();
        for (int i = 0; i < users.size(); i++) {
            userIndex.put(users.get(i).value(), i + 1);
        }
        for (int i = 0; i < created.size(); i++) {
            Optional<RevisionSnapshot> row = stored(created.get(i));
            outcome.observedRevision(
                    "revision#r" + (i + 1),
                    row.map(found -> observed(found, userIndex, installer)).orElse(ObservedRevision.DELETED));
        }
    }

    private ObservedRevision observed(RevisionSnapshot row, Map<Long, Integer> userIndex, UserId installer) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", row.id().value());
        raw.put("parent", row.post().value());
        raw.put("author", row.author().map(UserId::value).orElse(0L));
        raw.put("autosave", row.autosave());
        raw.put("title", row.title());
        raw.put("content", row.content());
        raw.put("excerpt", row.excerpt());
        List<String> footnotes = new ArrayList<>();
        for (JsonNode value : row.meta("footnotes")) {
            footnotes.add(value.isTextual() ? value.textValue() : value.asText());
        }
        raw.put("footnotes", footnotes);
        raw.put("date_gmt", PostRecords.format(LocalDateTime.ofInstant(row.createdAt(), ZoneOffset.UTC)));
        // The row ids of this module are not post ids: the ids in the record are never looked up as revisions.
        return RevisionRecords.record(
                raw, List.of(), posts.postIndex(), userIndex, installer.value(), new RunWindow(started, Instant.now()));
    }

    /** Resets the scenario's limit and removes any revision row left above the baseline. */
    void cleanUp() throws Exception {
        TierBRevisionsKeep.OVERRIDE.set(OptionalInt.empty());
        track();
        // The posts' delete took the revisions of the posts the scenario inserted; a row that remains is removed.
        for (Long id : created) {
            Optional<RevisionSnapshot> row = stored(id);
            if (row.isPresent()) {
                posts.as(0, List.of(), () -> revisions.delete(row.get().id()));
            }
        }
    }

    private Optional<RevisionSnapshot> stored(long id) throws Exception {
        return scope.call(MAIN, () -> rows.findRevision(MAIN, RevisionId.of(id)).map(RevisionEntity::toSnapshot));
    }

    /** {@code r<k>} for a revision created during the scenario. */
    private String ref(RevisionId id) {
        int index = created.indexOf(id.value());
        return index >= 0 ? "r" + (index + 1) : "#?" + id.value();
    }

    private RevisionId revision(String ref) {
        int index = ScenarioRules.revisionIndex(ref);
        if (index < 1 || index > created.size()) {
            throw new MissingReference();
        }
        return RevisionId.of(created.get(index - 1));
    }

    private PostId post(int index) {
        return posts.post(index).orElseThrow(MissingReference::new);
    }

    /** A reference that names nothing (yet): the operation is recorded as missing, as the driver records it. */
    private static final class MissingReference extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
