package org.quarkpress.revisions;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.PostId;
import org.quarkpress.core.RevisionId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

/**
 * A revision or autosave as stored: the post it belongs to, the user who wrote it (none for the system), the copied
 * title, content and excerpt, the copied revisioned meta (each key to its values in order; the values handed out are
 * copies), whether it is an autosave, and when it was written (a regular revision carries the post's modification
 * time of that save; an autosave the time of its last write).
 */
public record RevisionSnapshot(
        RevisionId id,
        SiteId site,
        PostId post,
        Optional<UserId> author,
        String title,
        String content,
        String excerpt,
        Map<String, List<JsonNode>> meta,
        boolean autosave,
        Instant createdAt) {

    public RevisionSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(post, "post");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(excerpt, "excerpt");
        Objects.requireNonNull(createdAt, "createdAt");
        meta = copy(Objects.requireNonNull(meta, "meta"));
    }

    @Override
    public Map<String, List<JsonNode>> meta() {
        return copy(meta);
    }

    /** The values of one meta key, in order; empty when the key is absent. */
    public List<JsonNode> meta(String key) {
        List<JsonNode> values = meta.get(key);
        return values == null
                ? List.of()
                : values.stream().map(value -> value.<JsonNode>deepCopy()).toList();
    }

    private static Map<String, List<JsonNode>> copy(Map<String, List<JsonNode>> meta) {
        Map<String, List<JsonNode>> copy = new LinkedHashMap<>();
        meta.forEach((key, values) -> copy.put(
                Objects.requireNonNull(key, "meta key"),
                Objects.requireNonNull(values, "meta values").stream()
                        .map(value ->
                                Objects.requireNonNull(value, "meta value").<JsonNode>deepCopy())
                        .toList()));
        return Collections.unmodifiableMap(copy);
    }
}
