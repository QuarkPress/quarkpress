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
        List<RevisionEntity> existing = revisions.ofPost(site, post.id());
        Optional<RevisionEntity> latestRegular = Optional.empty();
        for (RevisionEntity row : existing) {
            if (!row.autosave) {
                latestRegular = Optional.of(row);
            }
        }
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
