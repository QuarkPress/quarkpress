package org.quarkpress.revisions;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What an autosave gives: a title, content or excerpt (a field not given is not compared and, on a new autosave, is
 * stored empty; on an updated one it keeps the autosave's value), and revisioned meta values by key (a key not given
 * keeps the autosave's value; a key that is not revisioned for the post's type is refused by the service).
 */
public record AutosaveChanges(
        Optional<String> title,
        Optional<String> content,
        Optional<String> excerpt,
        Optional<Map<String, JsonNode>> meta) {

    public AutosaveChanges {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(excerpt, "excerpt");
        Objects.requireNonNull(meta, "meta");
        meta = meta.filter(given -> !given.isEmpty()).map(AutosaveChanges::copy);
    }

    /** A builder with nothing given. */
    public static Builder builder() {
        return new Builder();
    }

    /** The given value of a revisioned field. */
    public Optional<String> field(RevisionField field) {
        return switch (field) {
            case TITLE -> title;
            case CONTENT -> content;
            case EXCERPT -> excerpt;
        };
    }

    private static Map<String, JsonNode> copy(Map<String, JsonNode> meta) {
        Map<String, JsonNode> copy = new LinkedHashMap<>();
        meta.forEach((key, value) -> copy.put(
                Objects.requireNonNull(key, "meta key"),
                Objects.requireNonNull(value, "meta value").<JsonNode>deepCopy()));
        return Collections.unmodifiableMap(copy);
    }

    /** Collects the given parts of an autosave. */
    public static final class Builder {
        private String title;
        private String content;
        private String excerpt;
        private Map<String, JsonNode> meta;

        private Builder() {}

        public Builder title(String title) {
            this.title = Objects.requireNonNull(title, "title");
            return this;
        }

        public Builder content(String content) {
            this.content = Objects.requireNonNull(content, "content");
            return this;
        }

        public Builder excerpt(String excerpt) {
            this.excerpt = Objects.requireNonNull(excerpt, "excerpt");
            return this;
        }

        /** Gives one meta value; the first call makes the meta part present. */
        public Builder meta(String key, JsonNode value) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            if (meta == null) {
                meta = new LinkedHashMap<>();
            }
            meta.put(key, value.deepCopy());
            return this;
        }

        public AutosaveChanges build() {
            return new AutosaveChanges(
                    Optional.ofNullable(title),
                    Optional.ofNullable(content),
                    Optional.ofNullable(excerpt),
                    Optional.ofNullable(meta));
        }
    }
}
