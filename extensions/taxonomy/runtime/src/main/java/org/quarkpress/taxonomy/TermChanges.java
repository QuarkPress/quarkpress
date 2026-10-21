package org.quarkpress.taxonomy;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * The fields of a term write. A field not given takes WordPress's default on create (no description, no parent, a
 * slug generated from the name) and keeps the stored value on update; an empty slug given on update generates it
 * again from the name. {@code parent} 0 (or less) means none. With {@code expectedVersion}, an update of a term that
 * changed since that version is refused.
 */
public record TermChanges(
        Optional<String> name,
        Optional<String> slug,
        Optional<String> description,
        OptionalLong parent,
        OptionalInt expectedVersion) {

    public TermChanges {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(expectedVersion, "expectedVersion");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private String name;
        private String slug;
        private String description;
        private OptionalLong parent = OptionalLong.empty();
        private OptionalInt expectedVersion = OptionalInt.empty();

        private Builder() {}

        public Builder name(String name) {
            this.name = Objects.requireNonNull(name, "name");
            return this;
        }

        public Builder slug(String slug) {
            this.slug = Objects.requireNonNull(slug, "slug");
            return this;
        }

        public Builder description(String description) {
            this.description = Objects.requireNonNull(description, "description");
            return this;
        }

        /** The parent term's id; 0 for none. */
        public Builder parent(long parent) {
            this.parent = OptionalLong.of(parent);
            return this;
        }

        public Builder expectedVersion(int version) {
            this.expectedVersion = OptionalInt.of(version);
            return this;
        }

        public TermChanges build() {
            return new TermChanges(
                    Optional.ofNullable(name),
                    Optional.ofNullable(slug),
                    Optional.ofNullable(description),
                    parent,
                    expectedVersion);
        }
    }
}
