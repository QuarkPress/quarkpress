package org.quarkpress.revisions.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.quarkpress.core.PostId;
import org.quarkpress.core.QuarkPressClock;
import org.quarkpress.core.RevisionId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.ContentSanitizer;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.posts.runtime.Acting;
import org.quarkpress.posts.runtime.MetaRows;
import org.quarkpress.posts.runtime.PostEntity;
import org.quarkpress.revisions.AutosaveChanges;
import org.quarkpress.revisions.RevisionField;
import org.quarkpress.revisions.RevisionSnapshot;

/**
 * WordPress's revision writes, without capability checks (callers check before they call them): the caller holds the
 * site's post lock. Once a call writes, any failure marks the transaction for rollback.
 */
@ApplicationScoped
public class RevisionWrites {

    @Inject
    RevisionRepository revisions;

    @Inject
    RevisionPolicy policy;

    @Inject
    PostTypes types;

    @Inject
    ContentSanitizer sanitizer;

    @Inject
    Acting acting;

    @Inject
    QuarkPressClock clock;

    @Inject
    RevisionEvents events;

    @Inject
    TransactionSynchronizationRegistry registry;

    /**
     * WordPress's revision save for a post just written, on its saved state: nothing while the module autosaves, for
     * an auto-draft, for a type without revisions support, for a limit of 0, or for a post unchanged since its latest
     * regular revision (when there is none, autosaves aside, a revision is always saved); else the copy, then the
     * trimming of the oldest revisions beyond the limit. The caller holds the site's post lock.
     */
    public Optional<RevisionSnapshot> saveRevision(SiteId site, PostSnapshot post) {
        if (Autosaving.active() || post.status().equals("auto-draft") || !types.supports(post.type(), "revisions")) {
            return Optional.empty();
        }
        int keep = policy.keep(post);
        if (keep == 0) {
            return Optional.empty();
        }
        Optional<RevisionEntity> latestRegular = revisions.latestRegular(site, post.id());
        if (latestRegular.isPresent() && !policy.changed(latestRegular.get().toSnapshot(), post)) {
            return Optional.empty();
        }
        Set<RevisionField> fields = policy.fields(post);
        return Optional.of(written(() -> {
            RevisionSnapshot saved = insert(
                    site,
                    post.id(),
                    acting.user(),
                    fields.contains(RevisionField.TITLE) ? post.title() : "",
                    fields.contains(RevisionField.CONTENT) ? post.content() : "",
                    fields.contains(RevisionField.EXCERPT) ? post.excerpt() : "",
                    revisionedMeta(policy.metaKeys(post.type()), post.meta()),
                    false,
                    post.modifiedAt());
            trim(site, post.id(), keep);
            return saved;
        }));
    }

    /** Removes the oldest revisions beyond {@code keep}, autosaves skipped without replacement; rows locked by id. */
    private void trim(SiteId site, PostId post, int keep) {
        if (keep < 0) {
            return;
        }
        List<RevisionEntity> rows = revisions.lockOfPost(site, post);
        List<RevisionId> candidates = RevisionRules.trimCandidates(
                rows.stream().map(RevisionEntity::toSnapshot).toList(), keep);
        for (RevisionEntity row : rows) {
            if (candidates.contains(RevisionId.of(row.id))) {
                delete(site, row);
            }
        }
    }

    /**
     * Writes a revision or autosave row: the texts are filtered for the acting caller as a save filters them, the row
     * is persisted and flushed, and {@code RevisionCreated} is announced.
     */
    public RevisionSnapshot insert(
            SiteId site,
            PostId post,
            Optional<UserId> author,
            String title,
            String content,
            String excerpt,
            Map<String, List<JsonNode>> meta,
            boolean autosave,
            Instant createdAt) {
        var filters = acting.saveFilters(site);
        RevisionEntity row = RevisionEntity.create(site, post);
        row.authorId = author.map(UserId::value).orElse(null);
        row.title = sanitizer.title(title, filters);
        row.content = sanitizer.content(content, filters);
        row.excerpt = sanitizer.excerpt(excerpt, filters);
        ObjectNode json = JsonNodeFactory.instance.objectNode();
        meta.forEach((key, values) -> {
            ArrayNode array = json.putArray(key);
            values.forEach(value -> array.add(value.deepCopy()));
        });
        row.meta = json;
        row.autosave = autosave;
        row.createdAt = createdAt;
        revisions.persist(row);
        revisions.flush();
        RevisionSnapshot snapshot = row.toSnapshot();
        events.created(site, now(), snapshot);
        return snapshot;
    }

    /**
     * Creates the caller's first autosave of a post, without comparison: the revisioned fields given are stored (the
     * others empty), after the post's revisioned meta, which the given meta keys then replace; announced with
     * {@code RevisionCreated} and {@code AutosaveSaved(updated = false)}. The caller holds the site's post lock.
     */
    public RevisionSnapshot createAutosave(
            SiteId site,
            PostSnapshot post,
            Optional<UserId> author,
            AutosaveChanges changes,
            Set<RevisionField> fields,
            Set<String> metaKeys,
            Instant now) {
        return written(() -> {
            Map<String, List<JsonNode>> meta = new LinkedHashMap<>(revisionedMeta(metaKeys, post.meta()));
            changes.meta().ifPresent(given -> given.forEach((key, value) -> meta.put(key, List.of(value))));
            RevisionSnapshot created = insert(
                    site,
                    post.id(),
                    author,
                    given(changes, fields, RevisionField.TITLE),
                    given(changes, fields, RevisionField.CONTENT),
                    given(changes, fields, RevisionField.EXCERPT),
                    meta,
                    true,
                    now);
            events.autosaved(site, now(), created, false);
            return created;
        });
    }

    /** The meta object with each given key's values replaced by the one given value. */
    static ObjectNode replaced(JsonNode meta, Map<String, JsonNode> given) {
        ObjectNode result = MetaRows.copy(meta);
        given.forEach((key, value) -> {
            MetaRows.delete(result, key);
            MetaRows.add(result, key, value, false);
        });
        return result;
    }

    private static String given(AutosaveChanges changes, Set<RevisionField> fields, RevisionField field) {
        return fields.contains(field) ? RevisionRules.autosaveField(changes, field) : "";
    }

    /**
     * Updates the caller's autosave with what the autosave gives: the given fields (those revisioned, filtered for
     * the caller as a save filters them) replace the row's, the given meta keys replace the row's, the author and the
     * time are set, the row is flushed and {@code AutosaveSaved(updated = true)} is announced. The caller holds the
     * site's post lock and the row.
     */
    public RevisionSnapshot updateAutosave(
            SiteId site,
            RevisionEntity row,
            Optional<UserId> author,
            AutosaveChanges changes,
            Set<RevisionField> fields,
            Instant now) {
        return written(() -> {
            var filters = acting.saveFilters(site);
            if (fields.contains(RevisionField.TITLE) && changes.title().isPresent()) {
                row.title = sanitizer.title(changes.title().get(), filters);
            }
            if (fields.contains(RevisionField.CONTENT) && changes.content().isPresent()) {
                row.content = sanitizer.content(changes.content().get(), filters);
            }
            if (fields.contains(RevisionField.EXCERPT) && changes.excerpt().isPresent()) {
                row.excerpt = sanitizer.excerpt(changes.excerpt().get(), filters);
            }
            changes.meta().ifPresent(given -> row.meta = replaced(row.meta, given));
            row.authorId = author.map(UserId::value).orElse(null);
            row.createdAt = now;
            revisions.flush();
            RevisionSnapshot after = row.toSnapshot();
            events.autosaved(site, now(), after, true);
            return after;
        });
    }

    /**
     * The revision's revisioned meta replaces the parent's: each of {@code keys} has its values deleted, then the
     * revision's values of that key are added. The caller holds the site's post lock and the parent row.
     */
    public void restoreMeta(PostEntity parent, Set<String> keys, RevisionSnapshot revision) {
        ObjectNode meta = MetaRows.copy(parent.meta);
        for (String key : keys) {
            MetaRows.delete(meta, key);
            for (JsonNode value : revision.meta(key)) {
                MetaRows.add(meta, key, value, false);
            }
        }
        parent.meta = meta;
    }

    /** Removes every revision of the post, oldest first, each announced; rows locked in id order. */
    public void deleteAllOf(SiteId site, PostId post) {
        written(() -> {
            for (RevisionEntity row : revisions.lockOfPost(site, post)) {
                delete(site, row);
            }
            return null;
        });
    }

    /** Removes the locked row and announces {@code RevisionDeleted}. */
    public void delete(SiteId site, RevisionEntity row) {
        RevisionSnapshot before = row.toSnapshot();
        revisions.delete(row);
        revisions.flush();
        events.deleted(site, now(), before);
    }

    /** The entries of {@code meta} whose key is revisioned, in the meta's order. */
    public Map<String, List<JsonNode>> revisionedMeta(Set<String> keys, Map<String, List<JsonNode>> meta) {
        Map<String, List<JsonNode>> kept = new LinkedHashMap<>();
        meta.forEach((key, values) -> {
            if (keys.contains(key)) {
                kept.put(key, values);
            }
        });
        return kept;
    }

    private Instant now() {
        return clock.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** Runs the writes; any failure marks the transaction for rollback, so a failed call changes nothing. */
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
