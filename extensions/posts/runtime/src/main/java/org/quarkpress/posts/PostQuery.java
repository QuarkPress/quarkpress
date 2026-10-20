package org.quarkpress.posts;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import org.quarkpress.core.PostId;
import org.quarkpress.core.UserId;

/**
 * A query for posts of the current site, as WordPress's {@code WP_Query} reads its arguments. Empty lists and absent
 * values apply no condition. {@code types} are sanitized as keys; a list holding only {@code any} means the searchable
 * types (an {@code any} next to other types matches nothing, as in WordPress); empty means {@code post}. {@code
 * statuses} are sanitized as keys and may hold {@code any} (every status but those left out of searches, unless named
 * too); a list naming only unregistered statuses applies no status condition, as in WordPress. No status means
 * published posts plus the private posts the caller may read; the system identity counts as logged out for this. With
 * statuses given, a caller other than the system sees others' private posts only with the type's {@code
 * read_private_posts}, others' posts of the other registered non-public statuses only with its {@code
 * edit_others_posts} (its own with {@code edit_posts}), posts of unregistered statuses only with {@code
 * edit_others_posts}, and an {@code inherit} post when it has no parent, is the caller's own, has a parent shown by
 * the same rules, or has a parent whose status the post's own type lets the caller read ({@code read_private_posts}
 * for a private parent, {@code edit_others_posts} for any other non-public one).
 *
 * <p>A query by id or slug (a slug that is blank once trimmed counts as none) is never paged, and each post it finds is
 * checked as WordPress checks a single post whose status was not asked for: a public one is shown to anyone; a
 * logged-out caller (the system included) sees no other; a protected status (draft, pending, future) needs {@code
 * edit_post}, a private one {@code read_post}; any other registered status (trash, auto-draft) is never shown; an
 * unregistered one needs {@code edit_post}.
 *
 * <p>{@code authorNotIn} wins over {@code author} and {@code authorIn}; {@code parent} (0 means top level) over {@code
 * parentIn}, which wins over {@code parentNotIn}; {@code id} over {@code postIn}, which wins over {@code postNotIn}.
 * {@code name} and {@code title} are trimmed as PHP trims, and a blank one applies no condition; {@code title} matches
 * the whole title, ignoring case. {@code menuOrder} counts as its absolute value. {@code perPage} absent or 0 takes the
 * site's {@code posts_per_page} option (0 there means 1); -1 means no paging; below -1 counts as its absolute value.
 * {@code page} counts from 1 (0 means 1; a negative page counts as its absolute value); {@code offset} replaces the
 * page's start.
 *
 * <p>{@code search} (WordPress's {@code s}) is split into terms as WordPress splits it, or is one term with {@code
 * sentence}; an empty search, or one longer than {@link #MAX_SEARCH_BYTES} bytes, searches nothing. Each term must
 * appear in the title, the excerpt or the content, and a term starting with a hyphen in none of them. Matching ignores
 * case but not accents, and {@code %} and {@code _} match themselves. A caller that is not logged in (the system
 * included) finds no password-protected post. Without types, a query given a search (even an empty or over-long one)
 * that does not look for one post by id or slug covers every searchable type. With no order, or
 * {@code RELEVANCE} first, results are ranked as WordPress ranks them, then ordered by date (newest first unless the
 * relevance key is ascending) and id; see {@link OrderKey}.
 */
public record PostQuery(
        List<String> types,
        List<String> statuses,
        Optional<UserId> author,
        Set<UserId> authorIn,
        Set<UserId> authorNotIn,
        OptionalLong parent,
        Set<PostId> parentIn,
        Set<PostId> parentNotIn,
        Optional<PostId> id,
        List<PostId> postIn,
        Set<PostId> postNotIn,
        Optional<String> name,
        Optional<String> title,
        OptionalInt menuOrder,
        Optional<String> search,
        boolean sentence,
        List<PostOrder> orderBy,
        OptionalInt perPage,
        int page,
        OptionalInt offset) {

    /**
     * The longest search string, in bytes of UTF-8, that is searched for; a longer one is ignored, as WordPress empties
     * it before searching.
     */
    public static final int MAX_SEARCH_BYTES = 1600;

    public PostQuery {
        types = List.copyOf(types);
        statuses = List.copyOf(statuses);
        Objects.requireNonNull(author, "author");
        authorIn = Set.copyOf(authorIn);
        authorNotIn = Set.copyOf(authorNotIn);
        Objects.requireNonNull(parent, "parent");
        parentIn = Set.copyOf(parentIn);
        parentNotIn = Set.copyOf(parentNotIn);
        Objects.requireNonNull(id, "id");
        postIn = List.copyOf(postIn);
        postNotIn = Set.copyOf(postNotIn);
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(menuOrder, "menuOrder");
        Objects.requireNonNull(search, "search");
        orderBy = List.copyOf(orderBy);
        Objects.requireNonNull(perPage, "perPage");
        Objects.requireNonNull(offset, "offset");
    }

    /** A query for one post, by id or by a slug that is not blank: never paged, and each post found is checked. */
    public boolean singular() {
        return id.isPresent() || slug().isPresent();
    }

    /**
     * Whether the query searches: a search string that is not empty and holds at most {@link #MAX_SEARCH_BYTES} bytes
     * in UTF-8 (a longer one is ignored, as if absent).
     */
    public boolean searching() {
        return search.isPresent()
                && !search.get().isEmpty()
                && search.get().getBytes(StandardCharsets.UTF_8).length <= MAX_SEARCH_BYTES;
    }

    /** The slug asked for, trimmed as PHP trims; empty when absent or blank. */
    public Optional<String> slug() {
        return trimmed(name);
    }

    /** The title asked for, trimmed as PHP trims; empty when absent or blank. */
    public Optional<String> exactTitle() {
        return trimmed(title);
    }

    private static Optional<String> trimmed(Optional<String> value) {
        return value.map(PostQuery::phpTrim).filter(text -> !text.isEmpty());
    }

    /** PHP's trim: space, tab, line feed, carriage return, NUL and vertical tab from both ends. */
    private static String phpTrim(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && phpBlank(text.charAt(start))) {
            start++;
        }
        while (end > start && phpBlank(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean phpBlank(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\0' || c == '\u000B';
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.types.addAll(types);
        builder.statuses.addAll(statuses);
        builder.author = author;
        builder.authorIn.addAll(authorIn);
        builder.authorNotIn.addAll(authorNotIn);
        builder.parent = parent;
        builder.parentIn.addAll(parentIn);
        builder.parentNotIn.addAll(parentNotIn);
        builder.id = id;
        builder.postIn.addAll(postIn);
        builder.postNotIn.addAll(postNotIn);
        builder.name = name;
        builder.title = title;
        builder.menuOrder = menuOrder;
        builder.search = search;
        builder.sentence = sentence;
        builder.orderBy.addAll(orderBy);
        builder.perPage = perPage;
        builder.page = page;
        builder.offset = offset;
        return builder;
    }

    public static final class Builder {
        private final List<String> types = new ArrayList<>();
        private final List<String> statuses = new ArrayList<>();
        private Optional<UserId> author = Optional.empty();
        private final Set<UserId> authorIn = new LinkedHashSet<>();
        private final Set<UserId> authorNotIn = new LinkedHashSet<>();
        private OptionalLong parent = OptionalLong.empty();
        private final Set<PostId> parentIn = new LinkedHashSet<>();
        private final Set<PostId> parentNotIn = new LinkedHashSet<>();
        private Optional<PostId> id = Optional.empty();
        private final List<PostId> postIn = new ArrayList<>();
        private final Set<PostId> postNotIn = new LinkedHashSet<>();
        private Optional<String> name = Optional.empty();
        private Optional<String> title = Optional.empty();
        private OptionalInt menuOrder = OptionalInt.empty();
        private Optional<String> search = Optional.empty();
        private boolean sentence;
        private final List<PostOrder> orderBy = new ArrayList<>();
        private OptionalInt perPage = OptionalInt.empty();
        private int page = 1;
        private OptionalInt offset = OptionalInt.empty();

        private Builder() {}

        public Builder types(String... values) {
            types.clear();
            types.addAll(Arrays.asList(values));
            return this;
        }

        public Builder statuses(String... values) {
            statuses.clear();
            statuses.addAll(Arrays.asList(values));
            return this;
        }

        /** One author, as WordPress's {@code author} argument (an author archive: no sticky posts first). */
        public Builder author(UserId value) {
            this.author = Optional.of(value);
            return this;
        }

        public Builder authorIn(UserId... values) {
            authorIn.addAll(Arrays.asList(values));
            return this;
        }

        public Builder authorNotIn(UserId... values) {
            authorNotIn.addAll(Arrays.asList(values));
            return this;
        }

        /** The parent's id; 0 for top-level posts. */
        public Builder parent(long value) {
            this.parent = OptionalLong.of(value);
            return this;
        }

        public Builder parentIn(PostId... values) {
            parentIn.addAll(Arrays.asList(values));
            return this;
        }

        public Builder parentNotIn(PostId... values) {
            parentNotIn.addAll(Arrays.asList(values));
            return this;
        }

        public Builder id(PostId value) {
            this.id = Optional.of(value);
            return this;
        }

        public Builder postIn(PostId... values) {
            postIn.addAll(Arrays.asList(values));
            return this;
        }

        public Builder postNotIn(PostId... values) {
            postNotIn.addAll(Arrays.asList(values));
            return this;
        }

        /** A slug, sanitized as WordPress sanitizes a slug from a URL (accents kept). */
        public Builder name(String value) {
            this.name = Optional.of(value);
            return this;
        }

        /** An exact title. */
        public Builder title(String value) {
            this.title = Optional.of(value);
            return this;
        }

        public Builder clearTitle() {
            this.title = Optional.empty();
            return this;
        }

        public Builder menuOrder(int value) {
            this.menuOrder = OptionalInt.of(value);
            return this;
        }

        /** A search string (WordPress's {@code s}). */
        public Builder search(String value) {
            this.search = Optional.of(value);
            return this;
        }

        /** Searches for the whole string as one term (WordPress's {@code sentence}). */
        public Builder sentence(boolean value) {
            this.sentence = value;
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

        /** A start position that replaces the page's (WordPress's {@code offset}). */
        public Builder offset(int value) {
            this.offset = OptionalInt.of(value);
            return this;
        }

        public PostQuery build() {
            return new PostQuery(
                    types,
                    statuses,
                    author,
                    authorIn,
                    authorNotIn,
                    parent,
                    parentIn,
                    parentNotIn,
                    id,
                    postIn,
                    postNotIn,
                    name,
                    title,
                    menuOrder,
                    search,
                    sentence,
                    orderBy,
                    perPage,
                    page,
                    offset);
        }
    }
}
