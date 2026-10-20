package org.quarkpress.testing.wpscenario;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The arguments of a post query, as WordPress's {@code WP_Query} takes them; lists of users and posts hold
 * references. {@code orderBy} entries read {@code "<key> asc|desc"}.
 */
public record QueryArgs(
        List<String> types,
        List<String> statuses,
        List<Integer> authorIn,
        List<Integer> authorNotIn,
        OptionalInt parent,
        List<Integer> postIn,
        List<Integer> postNotIn,
        Optional<String> name,
        Optional<String> search,
        List<String> orderBy,
        OptionalInt perPage,
        OptionalInt page,
        OptionalInt offset,
        boolean stickyFirst) {

    static final List<String> KEYS = List.of(
            "types",
            "statuses",
            "author_in",
            "author_not_in",
            "parent",
            "post_in",
            "post_not_in",
            "name",
            "search",
            "orderby",
            "per_page",
            "page",
            "offset",
            "sticky_first");

    /** The order keys a scenario may use, as WordPress names them. */
    static final List<String> ORDER_KEYS = List.of(
            "date", "modified", "title", "name", "author", "parent", "type", "id", "menu_order", "post_in", "none");

    /**
     * The types a query may name: those both sides hold posts of. WordPress also writes revisions, which QuarkPress
     * does not keep.
     */
    static final List<String> TYPES = List.of("post", "page", "any");

    /** The statuses a query may name: the public, editorial and trash statuses both sides register. */
    static final List<String> STATUSES = List.of("publish", "future", "draft", "pending", "private", "trash", "any");

    public QueryArgs {
        types = List.copyOf(types);
        statuses = List.copyOf(statuses);
        authorIn = List.copyOf(authorIn);
        authorNotIn = List.copyOf(authorNotIn);
        Objects.requireNonNull(parent, "parent");
        postIn = List.copyOf(postIn);
        postNotIn = List.copyOf(postNotIn);
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(search, "search");
        orderBy = List.copyOf(orderBy);
        Objects.requireNonNull(perPage, "perPage");
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(offset, "offset");
        for (String type : types) {
            if (!TYPES.contains(type)) {
                throw new IllegalArgumentException(
                        "a scenario query type is one of " + String.join(", ", TYPES) + ": " + type);
            }
        }
        for (String status : statuses) {
            if (!STATUSES.contains(status)) {
                throw new IllegalArgumentException(
                        "a scenario query status is one of " + String.join(", ", STATUSES) + ": " + status);
            }
        }
        name.ifPresent(ScenarioRules::requirePostText);
        search.ifPresent(ScenarioRules::requirePostText);
        parent.ifPresent(ScenarioRules::requireReference);
        if (orderBy.size() > 1 && orderBy.stream().anyMatch(order -> order.startsWith("none "))) {
            throw new IllegalArgumentException("a scenario order by none is the only order");
        }
        if (unordered(orderBy) && (perPage.orElse(0) != -1 || page.isPresent() || offset.isPresent())) {
            throw new IllegalArgumentException(
                    "a scenario query ordered by none is not paged: per_page -1, no page or offset");
        }
        for (String order : orderBy) {
            int space = order.indexOf(' ');
            String key = space < 0 ? "" : order.substring(0, space);
            String direction = space < 0 ? "" : order.substring(space + 1);
            if (!ORDER_KEYS.contains(key) || !(direction.equals("asc") || direction.equals("desc"))) {
                throw new IllegalArgumentException("a scenario order is '<key> asc|desc' with a key among "
                        + String.join(", ", ORDER_KEYS) + ": " + order);
            }
        }
        for (List<Integer> references : List.of(authorIn, authorNotIn, postIn, postNotIn)) {
            if (references.stream().anyMatch(reference -> reference < 1)) {
                throw new IllegalArgumentException("a scenario query reference is positive");
            }
        }
    }

    /**
     * Whether the query orders by {@code none}: the database then returns the posts in any order, so both sides
     * record them as a sorted set, and such a query is not paged.
     */
    public boolean unordered() {
        return unordered(orderBy);
    }

    private static boolean unordered(List<String> orderBy) {
        return orderBy.size() == 1 && orderBy.get(0).startsWith("none ");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<String> types = new ArrayList<>();
        private final List<String> statuses = new ArrayList<>();
        private final List<Integer> authorIn = new ArrayList<>();
        private final List<Integer> authorNotIn = new ArrayList<>();
        private OptionalInt parent = OptionalInt.empty();
        private final List<Integer> postIn = new ArrayList<>();
        private final List<Integer> postNotIn = new ArrayList<>();
        private String name;
        private String search;
        private final List<String> orderBy = new ArrayList<>();
        private OptionalInt perPage = OptionalInt.empty();
        private OptionalInt page = OptionalInt.empty();
        private OptionalInt offset = OptionalInt.empty();
        private boolean stickyFirst;

        private Builder() {}

        public Builder types(String... values) {
            types.addAll(Arrays.asList(values));
            return this;
        }

        public Builder statuses(String... values) {
            statuses.addAll(Arrays.asList(values));
            return this;
        }

        public Builder authorIn(Integer... values) {
            authorIn.addAll(Arrays.asList(values));
            return this;
        }

        public Builder authorNotIn(Integer... values) {
            authorNotIn.addAll(Arrays.asList(values));
            return this;
        }

        public Builder parent(int value) {
            parent = OptionalInt.of(value);
            return this;
        }

        public Builder postIn(Integer... values) {
            postIn.addAll(Arrays.asList(values));
            return this;
        }

        public Builder postNotIn(Integer... values) {
            postNotIn.addAll(Arrays.asList(values));
            return this;
        }

        public Builder name(String value) {
            name = value;
            return this;
        }

        public Builder search(String value) {
            search = value;
            return this;
        }

        public Builder orderBy(String value) {
            orderBy.add(value);
            return this;
        }

        public Builder perPage(int value) {
            perPage = OptionalInt.of(value);
            return this;
        }

        public Builder page(int value) {
            page = OptionalInt.of(value);
            return this;
        }

        public Builder offset(int value) {
            offset = OptionalInt.of(value);
            return this;
        }

        public Builder stickyFirst(boolean value) {
            stickyFirst = value;
            return this;
        }

        public QueryArgs build() {
            return new QueryArgs(
                    types,
                    statuses,
                    authorIn,
                    authorNotIn,
                    parent,
                    postIn,
                    postNotIn,
                    Optional.ofNullable(name),
                    Optional.ofNullable(search),
                    orderBy,
                    perPage,
                    page,
                    offset,
                    stickyFirst);
        }
    }
}
