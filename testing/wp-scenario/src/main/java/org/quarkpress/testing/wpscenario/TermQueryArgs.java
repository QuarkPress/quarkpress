package org.quarkpress.testing.wpscenario;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The arguments of a term query in the scenario format: WordPress's {@code get_terms} arguments, with terms named by
 * reference and posts by index. A query ordered by {@code none} is compared as a sorted set.
 */
public record TermQueryArgs(
        List<String> taxonomies,
        Optional<String> orderBy,
        Optional<String> order,
        Optional<Boolean> hideEmpty,
        Optional<Boolean> hierarchical,
        boolean padCounts,
        boolean all,
        List<String> include,
        List<String> exclude,
        List<String> excludeTree,
        List<String> slugs,
        List<String> names,
        Optional<String> search,
        Optional<String> nameLike,
        Optional<String> descriptionLike,
        Optional<String> parent,
        Optional<String> childOf,
        boolean childless,
        List<Integer> posts,
        OptionalInt number,
        OptionalInt offset) {

    static final List<String> KEYS = List.of(
            "taxonomies",
            "orderby",
            "order",
            "hide_empty",
            "hierarchical",
            "pad_counts",
            "get_all",
            "include",
            "exclude",
            "exclude_tree",
            "slugs",
            "names",
            "search",
            "name_like",
            "description_like",
            "parent",
            "child_of",
            "childless",
            "posts",
            "number",
            "offset");

    public TermQueryArgs {
        taxonomies = List.copyOf(taxonomies);
        taxonomies.forEach(ScenarioRules::requireTermTaxonomy);
        include = List.copyOf(include);
        exclude = List.copyOf(exclude);
        excludeTree = List.copyOf(excludeTree);
        for (List<String> refs : List.of(include, exclude, excludeTree)) {
            refs.forEach(ref -> ScenarioRules.requireTermRef(ref, false));
        }
        slugs = List.copyOf(slugs);
        names = List.copyOf(names);
        slugs.forEach(ScenarioRules::requireTermName);
        names.forEach(ScenarioRules::requireTermName);
        Objects.requireNonNull(orderBy, "orderBy");
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(hideEmpty, "hideEmpty");
        Objects.requireNonNull(hierarchical, "hierarchical");
        Objects.requireNonNull(search, "search");
        Objects.requireNonNull(nameLike, "nameLike");
        Objects.requireNonNull(descriptionLike, "descriptionLike");
        for (Optional<String> text : List.of(orderBy, order, search, nameLike, descriptionLike)) {
            text.ifPresent(ScenarioRules::requireTermText);
        }
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(childOf, "childOf");
        parent.ifPresent(ref -> ScenarioRules.requireTermRef(ref, true));
        childOf.ifPresent(ref -> ScenarioRules.requireTermRef(ref, false));
        String resolving = taxonomies.isEmpty() ? "category" : taxonomies.get(0);
        for (List<String> refs : List.of(include, exclude, excludeTree)) {
            refs.forEach(ref -> ScenarioRules.requireDefaultIn(resolving, ref));
        }
        parent.ifPresent(ref -> ScenarioRules.requireDefaultIn(resolving, ref));
        childOf.ifPresent(ref -> ScenarioRules.requireDefaultIn(resolving, ref));
        posts = List.copyOf(posts);
        posts.forEach(ScenarioRules::requirePost);
        Objects.requireNonNull(number, "number");
        Objects.requireNonNull(offset, "offset");
    }

    /**
     * The taxonomy in which the query's term references resolve {@code default}: the first of {@code taxonomies}, or
     * {@code category} when none is given. Genre's default term is out of reach of a query whose first taxonomy is
     * category.
     */
    public String defaultTaxonomy() {
        return taxonomies.isEmpty() ? "category" : taxonomies.get(0);
    }

    /** Whether the query leaves the order to the database (orderby none): compared as a sorted set. */
    public boolean unordered() {
        return orderBy.map(text -> ScenarioRules.lowerAscii(text).equals("none"))
                .orElse(false);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private List<String> taxonomies = List.of();
        private String orderBy;
        private String order;
        private Boolean hideEmpty;
        private Boolean hierarchical;
        private boolean padCounts;
        private boolean all;
        private List<String> include = List.of();
        private List<String> exclude = List.of();
        private List<String> excludeTree = List.of();
        private List<String> slugs = List.of();
        private List<String> names = List.of();
        private String search;
        private String nameLike;
        private String descriptionLike;
        private String parent;
        private String childOf;
        private boolean childless;
        private List<Integer> posts = List.of();
        private Integer number;
        private Integer offset;

        public Builder taxonomies(List<String> value) {
            this.taxonomies = value;
            return this;
        }

        public Builder orderBy(String value) {
            this.orderBy = value;
            return this;
        }

        public Builder order(String value) {
            this.order = value;
            return this;
        }

        public Builder hideEmpty(boolean value) {
            this.hideEmpty = value;
            return this;
        }

        public Builder hierarchical(boolean value) {
            this.hierarchical = value;
            return this;
        }

        public Builder padCounts(boolean value) {
            this.padCounts = value;
            return this;
        }

        public Builder all(boolean value) {
            this.all = value;
            return this;
        }

        public Builder include(List<String> value) {
            this.include = value;
            return this;
        }

        public Builder exclude(List<String> value) {
            this.exclude = value;
            return this;
        }

        public Builder excludeTree(List<String> value) {
            this.excludeTree = value;
            return this;
        }

        public Builder slugs(List<String> value) {
            this.slugs = value;
            return this;
        }

        public Builder names(List<String> value) {
            this.names = value;
            return this;
        }

        public Builder search(String value) {
            this.search = value;
            return this;
        }

        public Builder nameLike(String value) {
            this.nameLike = value;
            return this;
        }

        public Builder descriptionLike(String value) {
            this.descriptionLike = value;
            return this;
        }

        public Builder parent(String ref) {
            this.parent = ref;
            return this;
        }

        public Builder childOf(String ref) {
            this.childOf = ref;
            return this;
        }

        public Builder childless(boolean value) {
            this.childless = value;
            return this;
        }

        public Builder posts(List<Integer> value) {
            this.posts = value;
            return this;
        }

        public Builder number(int value) {
            this.number = value;
            return this;
        }

        public Builder offset(int value) {
            this.offset = value;
            return this;
        }

        public TermQueryArgs build() {
            return new TermQueryArgs(
                    taxonomies,
                    Optional.ofNullable(orderBy),
                    Optional.ofNullable(order),
                    Optional.ofNullable(hideEmpty),
                    Optional.ofNullable(hierarchical),
                    padCounts,
                    all,
                    include,
                    exclude,
                    excludeTree,
                    slugs,
                    names,
                    Optional.ofNullable(search),
                    Optional.ofNullable(nameLike),
                    Optional.ofNullable(descriptionLike),
                    Optional.ofNullable(parent),
                    Optional.ofNullable(childOf),
                    childless,
                    posts,
                    number == null ? OptionalInt.empty() : OptionalInt.of(number),
                    offset == null ? OptionalInt.empty() : OptionalInt.of(offset));
        }
    }
}
