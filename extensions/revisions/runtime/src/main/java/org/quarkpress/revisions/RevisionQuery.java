package org.quarkpress.revisions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import org.quarkpress.core.RevisionId;
import org.quarkpress.core.UserId;

/**
 * What {@code Revisions.ofPost} returns (WordPress's {@code wp_get_post_revisions} arguments and the list parameters
 * of its REST revisions controller): ordered by {@code order} (see {@link RevisionOrder}; by time, newest first,
 * unless ascending), at most {@code limit} after skipping {@code offset}, only the ids of {@code include} (when any
 * are given), none of {@code exclude}, those matching {@code search} in the title, excerpt or content (parsed as the
 * posts module parses a search: terms, quoted phrases, a leading hyphen excludes; ignoring case), only one author's,
 * autosaves included, only, or excluded; with {@code checkEnabled} (the default) a post whose limit is 0 lists
 * nothing. {@code RELEVANCE} needs a search and {@code INCLUDE} an include list.
 */
public record RevisionQuery(
        boolean ascending,
        RevisionOrder order,
        OptionalInt limit,
        OptionalInt offset,
        List<RevisionId> include,
        List<RevisionId> exclude,
        Optional<String> search,
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
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(limit, "limit");
        Objects.requireNonNull(offset, "offset");
        include = List.copyOf(Objects.requireNonNull(include, "include"));
        exclude = List.copyOf(Objects.requireNonNull(exclude, "exclude"));
        Objects.requireNonNull(search, "search");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(autosaves, "autosaves");
        if (limit.isPresent() && limit.getAsInt() < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        if (offset.isPresent() && offset.getAsInt() < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (order == RevisionOrder.RELEVANCE
                && search.filter(given -> !given.isEmpty()).isEmpty()) {
            throw new IllegalArgumentException("ordering by relevance needs a search");
        }
        if (order == RevisionOrder.INCLUDE && include.isEmpty()) {
            throw new IllegalArgumentException("ordering by include needs an include list");
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
        private RevisionOrder order = RevisionOrder.DATE;
        private OptionalInt limit = OptionalInt.empty();
        private OptionalInt offset = OptionalInt.empty();
        private final List<RevisionId> include = new ArrayList<>();
        private final List<RevisionId> exclude = new ArrayList<>();
        private Optional<String> search = Optional.empty();
        private Optional<UserId> author = Optional.empty();
        private Autosaves autosaves = Autosaves.INCLUDE;
        private boolean checkEnabled = true;

        private Builder() {}

        public Builder ascending(boolean ascending) {
            this.ascending = ascending;
            return this;
        }

        public Builder order(RevisionOrder order) {
            this.order = Objects.requireNonNull(order, "order");
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

        /** Adds ids the query is restricted to, in the order {@code INCLUDE} lists them. */
        public Builder include(RevisionId... ids) {
            for (RevisionId id : ids) {
                include.add(Objects.requireNonNull(id, "id"));
            }
            return this;
        }

        /** Adds ids the query leaves out. */
        public Builder exclude(RevisionId... ids) {
            for (RevisionId id : ids) {
                exclude.add(Objects.requireNonNull(id, "id"));
            }
            return this;
        }

        public Builder search(String search) {
            this.search = Optional.of(Objects.requireNonNull(search, "search"));
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
            return new RevisionQuery(
                    ascending, order, limit, offset, include, exclude, search, author, autosaves, checkEnabled);
        }
    }
}
