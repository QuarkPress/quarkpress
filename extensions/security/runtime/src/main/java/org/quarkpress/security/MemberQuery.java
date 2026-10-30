package org.quarkpress.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.quarkpress.core.UserId;

/**
 * Which members of a site {@link Memberships#members} lists and in what order. Every filter left empty filters
 * nothing, except {@code publishedPostTypes}, where an empty list matches nobody. {@code search} looks in
 * {@code searchColumns} only (none: it matches nobody). {@code offset} is at least 0 and {@code limit} at least 1.
 */
public record MemberQuery(
        List<String> roles,
        List<String> capabilities,
        Optional<String> search,
        Set<MemberColumn> searchColumns,
        List<UserId> include,
        List<UserId> exclude,
        List<String> slugs,
        Optional<List<String>> publishedPostTypes,
        MemberOrder orderBy,
        boolean ascending,
        int offset,
        int limit) {

    public MemberQuery {
        roles = List.copyOf(Objects.requireNonNull(roles, "roles"));
        capabilities = List.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        Objects.requireNonNull(search, "search");
        searchColumns = Set.copyOf(Objects.requireNonNull(searchColumns, "searchColumns"));
        include = List.copyOf(Objects.requireNonNull(include, "include"));
        exclude = List.copyOf(Objects.requireNonNull(exclude, "exclude"));
        slugs = List.copyOf(Objects.requireNonNull(slugs, "slugs"));
        publishedPostTypes =
                Objects.requireNonNull(publishedPostTypes, "publishedPostTypes").map(List::copyOf);
        Objects.requireNonNull(orderBy, "orderBy");
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative: " + offset);
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1: " + limit);
        }
    }

    /** A builder whose defaults filter nothing and give the first 10 members by display name, ascending. */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private List<String> roles = List.of();
        private List<String> capabilities = List.of();
        private Optional<String> search = Optional.empty();
        private Set<MemberColumn> searchColumns = Set.of();
        private List<UserId> include = List.of();
        private List<UserId> exclude = List.of();
        private List<String> slugs = List.of();
        private Optional<List<String>> publishedPostTypes = Optional.empty();
        private MemberOrder orderBy = MemberOrder.NAME;
        private boolean ascending = true;
        private int offset = 0;
        private int limit = 10;

        private Builder() {}

        /** Members with any of these roles (one filter with {@link #capabilities}: either list matches). */
        public Builder roles(List<String> roles) {
            this.roles = new ArrayList<>(roles);
            return this;
        }

        /** Members storing any of these capabilities, or a role granting one, as a key (see {@link #roles}). */
        public Builder capabilities(List<String> capabilities) {
            this.capabilities = new ArrayList<>(capabilities);
            return this;
        }

        /** A case-insensitive substring of the {@link #searchColumns}; {@code %} and {@code _} are literal. */
        public Builder search(String term) {
            this.search = Optional.of(term);
            return this;
        }

        public Builder searchColumns(Set<MemberColumn> columns) {
            this.searchColumns = Set.copyOf(columns);
            return this;
        }

        public Builder include(List<UserId> users) {
            this.include = new ArrayList<>(users);
            return this;
        }

        /** Ignored when {@link #include} is given, as in WordPress. */
        public Builder exclude(List<UserId> users) {
            this.exclude = new ArrayList<>(users);
            return this;
        }

        /** Members whose nicename is one of these. */
        public Builder slugs(List<String> nicenames) {
            this.slugs = new ArrayList<>(nicenames);
            return this;
        }

        /** Only authors of at least one published post of these types on the site (needs the posts module). */
        public Builder publishedPostTypes(List<String> types) {
            this.publishedPostTypes = Optional.of(new ArrayList<>(types));
            return this;
        }

        public Builder orderBy(MemberOrder order) {
            this.orderBy = order;
            return this;
        }

        public Builder ascending(boolean ascending) {
            this.ascending = ascending;
            return this;
        }

        public Builder offset(int offset) {
            this.offset = offset;
            return this;
        }

        public Builder limit(int limit) {
            this.limit = limit;
            return this;
        }

        public MemberQuery build() {
            return new MemberQuery(
                    roles,
                    capabilities,
                    search,
                    searchColumns,
                    include,
                    exclude,
                    slugs,
                    publishedPostTypes,
                    orderBy,
                    ascending,
                    offset,
                    limit);
        }
    }
}
