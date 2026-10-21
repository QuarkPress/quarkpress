package org.quarkpress.taxonomy;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import org.quarkpress.core.PostId;
import org.quarkpress.core.TermId;

/**
 * A term query (WordPress's {@code WP_Term_Query} arguments), with the API's defaults: every taxonomy the caller may
 * read, ordered by name ascending, empty terms hidden (an empty term whose descendant has a count stays when the query
 * is hierarchical), hierarchical, no limit. {@code hideEmpty} is empty when the caller did not set it: then a query
 * hides empty terms and a count does not, as WordPress's term query and term count do. {@code order} is ascending only
 * for "ASC" in any letter case; anything else is descending. {@code number} and {@code offset} count as their absolute
 * values, 0 meaning no limit. {@code parent} 0 means the top level; a positive parent cancels {@code childOf}; a
 * negative parent or {@code childOf} matches nothing. {@code all} is WordPress's {@code get => 'all'}: no hiding, no
 * hierarchy, no {@code childOf} or {@code childless}. {@code posts} limits the result to terms of those posts and turns
 * {@code hideEmpty} off.
 */
public record TermQuery(
        List<String> taxonomies,
        TermOrder orderBy,
        String order,
        Optional<Boolean> hideEmpty,
        boolean hierarchical,
        boolean padCounts,
        boolean all,
        List<TermId> include,
        List<TermId> exclude,
        List<TermId> excludeTree,
        List<String> slugs,
        List<String> names,
        Optional<String> search,
        Optional<String> nameLike,
        Optional<String> descriptionLike,
        OptionalLong parent,
        long childOf,
        boolean childless,
        List<PostId> posts,
        int number,
        int offset) {

    public TermQuery {
        taxonomies = List.copyOf(taxonomies);
        Objects.requireNonNull(orderBy, "orderBy");
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(hideEmpty, "hideEmpty");
        include = List.copyOf(include);
        exclude = List.copyOf(exclude);
        excludeTree = List.copyOf(excludeTree);
        slugs = List.copyOf(slugs);
        names = List.copyOf(names);
        Objects.requireNonNull(search, "search");
        Objects.requireNonNull(nameLike, "nameLike");
        Objects.requireNonNull(descriptionLike, "descriptionLike");
        Objects.requireNonNull(parent, "parent");
        posts = List.copyOf(posts);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private List<String> taxonomies = List.of();
        private TermOrder orderBy = TermOrder.NAME;
        private String order = "ASC";
        private Boolean hideEmpty;
        private boolean hierarchical = true;
        private boolean padCounts;
        private boolean all;
        private List<TermId> include = List.of();
        private List<TermId> exclude = List.of();
        private List<TermId> excludeTree = List.of();
        private List<String> slugs = List.of();
        private List<String> names = List.of();
        private String search;
        private String nameLike;
        private String descriptionLike;
        private OptionalLong parent = OptionalLong.empty();
        private long childOf;
        private boolean childless;
        private List<PostId> posts = List.of();
        private int number;
        private int offset;

        private Builder() {}

        public Builder taxonomies(String... keys) {
            this.taxonomies = List.of(keys);
            return this;
        }

        public Builder orderBy(TermOrder orderBy) {
            this.orderBy = Objects.requireNonNull(orderBy, "orderBy");
            return this;
        }

        public Builder order(String order) {
            this.order = Objects.requireNonNull(order, "order");
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

        public Builder include(TermId... ids) {
            this.include = List.of(ids);
            return this;
        }

        public Builder exclude(TermId... ids) {
            this.exclude = List.of(ids);
            return this;
        }

        public Builder excludeTree(TermId... ids) {
            this.excludeTree = List.of(ids);
            return this;
        }

        public Builder slugs(String... slugs) {
            this.slugs = List.of(slugs);
            return this;
        }

        public Builder names(String... names) {
            this.names = List.of(names);
            return this;
        }

        public Builder search(String text) {
            this.search = Objects.requireNonNull(text, "text");
            return this;
        }

        public Builder nameLike(String text) {
            this.nameLike = Objects.requireNonNull(text, "text");
            return this;
        }

        public Builder descriptionLike(String text) {
            this.descriptionLike = Objects.requireNonNull(text, "text");
            return this;
        }

        public Builder parent(long parent) {
            this.parent = OptionalLong.of(parent);
            return this;
        }

        public Builder childOf(long term) {
            this.childOf = term;
            return this;
        }

        public Builder childless(boolean value) {
            this.childless = value;
            return this;
        }

        public Builder posts(PostId... posts) {
            this.posts = List.of(posts);
            return this;
        }

        public Builder number(int number) {
            this.number = number;
            return this;
        }

        public Builder offset(int offset) {
            this.offset = offset;
            return this;
        }

        public TermQuery build() {
            return new TermQuery(
                    taxonomies,
                    orderBy,
                    order,
                    Optional.ofNullable(hideEmpty),
                    hierarchical,
                    padCounts,
                    all,
                    include,
                    exclude,
                    excludeTree,
                    slugs,
                    names,
                    Optional.ofNullable(search).filter(text -> !text.isEmpty()),
                    Optional.ofNullable(nameLike).filter(text -> !text.isEmpty()),
                    Optional.ofNullable(descriptionLike).filter(text -> !text.isEmpty()),
                    parent,
                    childOf,
                    childless,
                    posts,
                    number,
                    offset);
        }
    }
}
