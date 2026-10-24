package org.quarkpress.revisions;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import org.quarkpress.core.UserId;

/**
 * What {@code Revisions.ofPost} returns (WordPress's {@code wp_get_post_revisions} arguments): newest first unless
 * ascending (by time, then id, both in that direction), at most {@code limit} after skipping {@code offset}, only one
 * author's, autosaves included, only, or excluded; with {@code checkEnabled} (the default) a post whose limit is 0
 * lists nothing.
 */
public record RevisionQuery(
        boolean ascending,
        OptionalInt limit,
        OptionalInt offset,
        Optional<UserId> author,
        Autosaves autosaves,
        boolean checkEnabled) {

    /** Which revisions a query returns by kind. */
    public enum Autosaves {
        INCLUDE,
        ONLY,
        EXCLUDE
    }

    public RevisionQuery {
        Objects.requireNonNull(limit, "limit");
        Objects.requireNonNull(offset, "offset");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(autosaves, "autosaves");
        if (limit.isPresent() && limit.getAsInt() < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        if (offset.isPresent() && offset.getAsInt() < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
    }

    /** Newest first, no limit, every revision, the post's limit checked. */
    public static RevisionQuery all() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builds a {@link RevisionQuery}. */
    public static final class Builder {

        private boolean ascending;
        private OptionalInt limit = OptionalInt.empty();
        private OptionalInt offset = OptionalInt.empty();
        private Optional<UserId> author = Optional.empty();
        private Autosaves autosaves = Autosaves.INCLUDE;
        private boolean checkEnabled = true;

        private Builder() {}

        public Builder ascending(boolean ascending) {
            this.ascending = ascending;
            return this;
        }

        public Builder limit(int limit) {
            this.limit = OptionalInt.of(limit);
            return this;
        }

        public Builder offset(int offset) {
            this.offset = OptionalInt.of(offset);
            return this;
        }

        public Builder author(UserId author) {
            this.author = Optional.of(Objects.requireNonNull(author, "author"));
            return this;
        }

        public Builder autosaves(Autosaves autosaves) {
            this.autosaves = Objects.requireNonNull(autosaves, "autosaves");
            return this;
        }

        public Builder checkEnabled(boolean checkEnabled) {
            this.checkEnabled = checkEnabled;
            return this;
        }

        public RevisionQuery build() {
            return new RevisionQuery(ascending, limit, offset, author, autosaves, checkEnabled);
        }
    }
}
