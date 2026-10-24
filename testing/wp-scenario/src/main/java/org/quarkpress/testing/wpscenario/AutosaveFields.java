package org.quarkpress.testing.wpscenario;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** The fields an autosave gives (absent: not given): any of title, content and excerpt. */
public record AutosaveFields(Optional<String> title, Optional<String> content, Optional<String> excerpt) {

    /** The keys of the scenario format. */
    static final List<String> KEYS = List.of("title", "content", "excerpt");

    public AutosaveFields {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(excerpt, "excerpt");
        title.ifPresent(ScenarioRules::requirePostText);
        content.ifPresent(ScenarioRules::requirePostText);
        excerpt.ifPresent(ScenarioRules::requirePostText);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String title;
        private String content;
        private String excerpt;

        private Builder() {}

        public Builder title(String value) {
            title = value;
            return this;
        }

        public Builder content(String value) {
            content = value;
            return this;
        }

        public Builder excerpt(String value) {
            excerpt = value;
            return this;
        }

        public AutosaveFields build() {
            return new AutosaveFields(
                    Optional.ofNullable(title), Optional.ofNullable(content), Optional.ofNullable(excerpt));
        }
    }
}
