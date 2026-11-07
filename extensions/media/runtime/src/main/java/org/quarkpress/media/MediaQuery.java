package org.quarkpress.media;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.DateCondition;
import org.quarkpress.posts.OrderKey;
import org.quarkpress.posts.PostOrder;

/**
 * A query of the current site's media library, run as a post query of attachments with the posts module's rules
 * ({@code PostQuery} describes each argument). {@code mimeTypes} filters by type as WordPress reads its MIME-type
 * argument: {@code image} and {@code image/*} find every image, {@code image/jpeg} one type, {@code *} any type, case
 * ignored; each value may be a comma-separated list, and a piece that is empty, {@code %} or {@code %/%} once its
 * whitespace is removed drops the whole filter (so does a trailing comma), as no value does. Only attachments with
 * media data are found. {@code parent} 0 finds the unattached attachments and wins over {@code parentIn}; {@code author} and {@code authorIn}
 * combine. {@code search} looks in titles, captions and descriptions. No {@code statuses} means {@code inherit}, plus
 * {@code private} for a caller who may read others' private attachments; statuses given are read as the posts module
 * reads them, so a caller sees only the attachments it may read.
 */
public record MediaQuery(
        List<String> mimeTypes,
        OptionalLong parent,
        List<Long> parentIn,
        Optional<UserId> author,
        List<UserId> authorIn,
        Optional<String> search,
        List<String> statuses,
        List<DateCondition> dates,
        List<PostOrder> orderBy,
        OptionalInt perPage,
        int page,
        OptionalInt offset) {

    public MediaQuery {
        mimeTypes = List.copyOf(mimeTypes);
        Objects.requireNonNull(parent, "parent");
        parentIn = List.copyOf(parentIn);
        for (long parentId : parentIn) {
            if (parentId <= 0) {
                throw new IllegalArgumentException("parentIn holds post ids; use parent(0) for unattached attachments");
            }
        }
        Objects.requireNonNull(author, "author");
        authorIn = List.copyOf(authorIn);
        Objects.requireNonNull(search, "search");
        statuses = List.copyOf(statuses);
        dates = List.copyOf(dates);
        orderBy = List.copyOf(orderBy);
        Objects.requireNonNull(perPage, "perPage");
        Objects.requireNonNull(offset, "offset");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<String> mimeTypes = new ArrayList<>();
        private OptionalLong parent = OptionalLong.empty();
        private final List<Long> parentIn = new ArrayList<>();
        private Optional<UserId> author = Optional.empty();
        private final List<UserId> authorIn = new ArrayList<>();
        private Optional<String> search = Optional.empty();
        private final List<String> statuses = new ArrayList<>();
        private final List<DateCondition> dates = new ArrayList<>();
        private final List<PostOrder> orderBy = new ArrayList<>();
        private OptionalInt perPage = OptionalInt.empty();
        private int page = 1;
        private OptionalInt offset = OptionalInt.empty();

        private Builder() {}

        /** The MIME types or patterns, any of which an attachment's type must match. */
        public Builder mimeTypes(List<String> values) {
            mimeTypes.clear();
            mimeTypes.addAll(values);
            return this;
        }

        /** The parent's id; 0 for the unattached attachments. */
        public Builder parent(long value) {
            this.parent = OptionalLong.of(value);
            return this;
        }

        /**
         * Parent ids, any of which an attachment's parent must be; never 0: {@link #parent parent(0)} asks for the
         * unattached ones.
         */
        public Builder parentIn(List<Long> values) {
            parentIn.clear();
            parentIn.addAll(values);
            return this;
        }

        public Builder author(UserId value) {
            this.author = Optional.of(Objects.requireNonNull(value, "author"));
            return this;
        }

        public Builder authorIn(List<UserId> values) {
            authorIn.clear();
            authorIn.addAll(values);
            return this;
        }

        /** A search string, as WordPress's {@code s}. */
        public Builder search(String value) {
            this.search = Optional.of(Objects.requireNonNull(value, "search"));
            return this;
        }

        public Builder statuses(List<String> values) {
            statuses.clear();
            statuses.addAll(values);
            return this;
        }

        /** Appends a date condition; all of them must hold. */
        public Builder date(DateCondition condition) {
            dates.add(Objects.requireNonNull(condition, "condition"));
            return this;
        }

        /** Appends an ordering key. */
        public Builder orderBy(OrderKey key, boolean ascending) {
            orderBy.add(new PostOrder(key, ascending));
            return this;
        }

        public Builder perPage(int value) {
            this.perPage = OptionalInt.of(value);
            return this;
        }

        public Builder page(int value) {
            this.page = value;
            return this;
        }

        /** A start position that replaces the page's. */
        public Builder offset(int value) {
            this.offset = OptionalInt.of(value);
            return this;
        }

        public MediaQuery build() {
            return new MediaQuery(
                    mimeTypes, parent, parentIn, author, authorIn, search, statuses, dates, orderBy, perPage, page,
                    offset);
        }
    }
}
