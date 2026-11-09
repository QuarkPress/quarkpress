package org.quarkpress.media;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.quarkpress.core.PostId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.DateCondition;
import org.quarkpress.posts.OrderKey;
import org.quarkpress.posts.PostOrder;
import org.quarkpress.posts.SearchColumn;

/**
 * A query of the current site's media library, run as a post query of attachments with the posts module's rules
 * ({@code PostQuery} describes each argument). {@code mimeTypes} filters by type as WordPress reads its MIME-type
 * argument: {@code image} and {@code image/*} find every image, {@code image/jpeg} one type, {@code *} any type, case
 * ignored; each value may be a comma-separated list, and a piece that is empty, {@code %} or {@code %/%} once its
 * whitespace is removed drops the whole filter (so does a trailing comma), as no value does. {@code exactMimeTypes},
 * when given, replaces it: an attachment's type must equal one of them, case included. Only attachments with media
 * data are found. {@code parent} 0 finds the unattached attachments and wins over {@code parentIn}, which wins over
 * {@code parentNotIn}; in both lists 0 stands for the unattached attachments, alone or next to post ids. {@code
 * author} and {@code authorIn} combine, {@code authorNotIn} wins over both; {@code include} (which {@code
 * OrderKey.POST_IN} orders by) wins over {@code exclude}; {@code slugs} matches any of the slugs given ({@code
 * OrderKey.NAME_IN} orders by them). {@code search} looks in titles, captions and descriptions
 * (or the {@code searchColumns} given, as {@code PostQuery} reads them, whole columns with {@code exactSearch}) and,
 * whatever the columns, in the attached file's path relative to the site's uploads folder (such as {@code
 * 2026/11/photo.jpg}). No {@code statuses} means {@code inherit}, plus {@code private} for a caller who may read
 * others' private attachments; statuses given are read as the posts module reads them, so a caller sees only the
 * attachments it may read. An {@code unfiltered} query reads the rows of its statuses whoever asks, whatever their
 * author and parent, without any capability, and counts them all: for a caller that decides per row what it shows.
 * A {@code countOnly} query answers the count alone: its page holds no attachment and no media row is read.
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
        OptionalInt offset,
        boolean unfiltered,
        List<String> exactMimeTypes,
        boolean exactSearch,
        List<SearchColumn> searchColumns,
        List<UserId> authorNotIn,
        List<Long> parentNotIn,
        List<PostId> include,
        List<PostId> exclude,
        List<String> slugs,
        boolean countOnly) {

    public MediaQuery {
        mimeTypes = List.copyOf(mimeTypes);
        Objects.requireNonNull(parent, "parent");
        parentIn = List.copyOf(parentIn);
        for (long parentId : parentIn) {
            if (parentId < 0) {
                throw new IllegalArgumentException("parentIn holds post ids, or 0 for the unattached attachments");
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
        exactMimeTypes = List.copyOf(exactMimeTypes);
        searchColumns = List.copyOf(searchColumns);
        authorNotIn = List.copyOf(authorNotIn);
        parentNotIn = List.copyOf(parentNotIn);
        for (long parentId : parentNotIn) {
            if (parentId < 0) {
                throw new IllegalArgumentException("parentNotIn holds post ids, or 0 for the unattached attachments");
            }
        }
        include = List.copyOf(include);
        exclude = List.copyOf(exclude);
        slugs = List.copyOf(slugs);
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
        private boolean unfiltered;
        private final List<String> exactMimeTypes = new ArrayList<>();
        private boolean exactSearch;
        private final List<SearchColumn> searchColumns = new ArrayList<>();
        private final List<UserId> authorNotIn = new ArrayList<>();
        private final List<Long> parentNotIn = new ArrayList<>();
        private final List<PostId> include = new ArrayList<>();
        private final List<PostId> exclude = new ArrayList<>();
        private final List<String> slugs = new ArrayList<>();
        private boolean countOnly;

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
         * Parent ids, any of which an attachment's parent must be; 0 adds the unattached attachments (WordPress's
         * {@code post_parent__in} holding 0).
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

        /**
         * Reads the rows of the statuses given (all authors), whoever asks and without any capability, all counted as
         * found: for a caller that decides per row what it shows.
         */
        public Builder unfiltered(boolean value) {
            this.unfiltered = value;
            return this;
        }

        /** The MIME types an attachment's type must equal one of, case included; replaces {@link #mimeTypes}. */
        public Builder exactMimeTypes(List<String> values) {
            exactMimeTypes.clear();
            exactMimeTypes.addAll(values);
            return this;
        }

        /** Matches each search term against whole columns (and the whole file path), not parts of them. */
        public Builder exactSearch(boolean value) {
            this.exactSearch = value;
            return this;
        }

        /** The post columns the search looks in; none means all three. The file path is searched whatever they are. */
        public Builder searchColumns(SearchColumn... columns) {
            searchColumns.clear();
            searchColumns.addAll(List.of(columns));
            return this;
        }

        /** Authors whose attachments are left out; wins over {@link #author} and {@link #authorIn}. */
        public Builder authorNotIn(List<UserId> values) {
            authorNotIn.clear();
            authorNotIn.addAll(values);
            return this;
        }

        /** Parent ids whose attachments are left out; 0 leaves out the unattached attachments. */
        public Builder parentNotIn(List<Long> values) {
            parentNotIn.clear();
            parentNotIn.addAll(values);
            return this;
        }

        /** The attachments to find, by id; {@code OrderKey.POST_IN} orders by this list. */
        public Builder include(List<PostId> values) {
            include.clear();
            include.addAll(values);
            return this;
        }

        /** Attachments left out, by id. */
        public Builder exclude(List<PostId> values) {
            exclude.clear();
            exclude.addAll(values);
            return this;
        }

        /** Slugs, any of which an attachment's slug must match; {@code OrderKey.NAME_IN} orders by this list. */
        public Builder slugs(List<String> values) {
            slugs.clear();
            slugs.addAll(values);
            return this;
        }

        /**
         * Counts the matching attachments without reading them: the page holds none, its count and number of pages
         * are those of the query (for an answer that shows no attachment, such as WordPress's {@code HEAD}).
         */
        public Builder countOnly(boolean value) {
            this.countOnly = value;
            return this;
        }

        public MediaQuery build() {
            return new MediaQuery(
                    mimeTypes,
                    parent,
                    parentIn,
                    author,
                    authorIn,
                    search,
                    statuses,
                    dates,
                    orderBy,
                    perPage,
                    page,
                    offset,
                    unfiltered,
                    exactMimeTypes,
                    exactSearch,
                    searchColumns,
                    authorNotIn,
                    parentNotIn,
                    include,
                    exclude,
                    slugs,
                    countOnly);
        }
    }
}
