package org.quarkpress.testing.wpscenario;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** The fields an insert or update of a term gives (absent: not given). {@code parent} is a term reference. */
public record TermFields(
        Optional<String> name, Optional<String> slug, Optional<String> description, Optional<String> parent) {

    static final List<String> KEYS = List.of("name", "slug", "description", "parent");

    public TermFields {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(parent, "parent");
        name.ifPresent(ScenarioRules::requireTermName);
        slug.ifPresent(ScenarioRules::requireTermName);
        description.ifPresent(ScenarioRules::requireTermText);
        parent.ifPresent(ref -> ScenarioRules.requireTermRef(ref, true));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String name;
        private String slug;
        private String description;
        private String parent;

        public Builder name(String value) {
            this.name = value;
            return this;
        }

        public Builder slug(String value) {
            this.slug = value;
            return this;
        }

        public Builder description(String value) {
            this.description = value;
            return this;
        }

        public Builder parent(String ref) {
            this.parent = ref;
            return this;
        }

        public TermFields build() {
            return new TermFields(
                    Optional.ofNullable(name),
                    Optional.ofNullable(slug),
                    Optional.ofNullable(description),
                    Optional.ofNullable(parent));
        }
    }
}
