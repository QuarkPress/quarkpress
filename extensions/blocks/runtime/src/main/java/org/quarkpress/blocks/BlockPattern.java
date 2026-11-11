package org.quarkpress.blocks;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A block pattern: a name, a title and block markup, and the optional properties WordPress's pattern registry knows.
 * An absent optional is a property the pattern does not set.
 */
public final class BlockPattern {

    private final String name;
    private final String title;
    private final String content;
    private final Optional<String> description;
    private final Optional<Integer> viewportWidth;
    private final Optional<Boolean> inserter;
    private final Optional<List<String>> categories;
    private final Optional<List<String>> keywords;
    private final Optional<List<String>> blockTypes;
    private final Optional<List<String>> postTypes;
    private final Optional<List<String>> templateTypes;
    private final Optional<String> source;

    private BlockPattern(Builder builder) {
        this.name = builder.name;
        this.title = builder.title;
        this.content = builder.content;
        this.description = builder.description;
        this.viewportWidth = builder.viewportWidth;
        this.inserter = builder.inserter;
        this.categories = builder.categories;
        this.keywords = builder.keywords;
        this.blockTypes = builder.blockTypes;
        this.postTypes = builder.postTypes;
        this.templateTypes = builder.templateTypes;
        this.source = builder.source;
    }

    /** A pattern named {@code name} (as {@code <namespace>/<name>} by convention), with its title and markup. */
    public static Builder builder(String name, String title, String content) {
        return new Builder(name, title, content);
    }

    public String name() {
        return name;
    }

    public String title() {
        return title;
    }

    /** The pattern's block markup as registered. */
    public String content() {
        return content;
    }

    public Optional<String> description() {
        return description;
    }

    public Optional<Integer> viewportWidth() {
        return viewportWidth;
    }

    public Optional<Boolean> inserter() {
        return inserter;
    }

    public Optional<List<String>> categories() {
        return categories;
    }

    public Optional<List<String>> keywords() {
        return keywords;
    }

    public Optional<List<String>> blockTypes() {
        return blockTypes;
    }

    public Optional<List<String>> postTypes() {
        return postTypes;
    }

    public Optional<List<String>> templateTypes() {
        return templateTypes;
    }

    public Optional<String> source() {
        return source;
    }

    @Override
    public String toString() {
        return "BlockPattern[" + name + "]";
    }

    /** Builds a {@link BlockPattern}. */
    public static final class Builder {

        private final String name;
        private final String title;
        private final String content;
        private Optional<String> description = Optional.empty();
        private Optional<Integer> viewportWidth = Optional.empty();
        private Optional<Boolean> inserter = Optional.empty();
        private Optional<List<String>> categories = Optional.empty();
        private Optional<List<String>> keywords = Optional.empty();
        private Optional<List<String>> blockTypes = Optional.empty();
        private Optional<List<String>> postTypes = Optional.empty();
        private Optional<List<String>> templateTypes = Optional.empty();
        private Optional<String> source = Optional.empty();

        private Builder(String name, String title, String content) {
            this.name = Objects.requireNonNull(name, "name");
            this.title = Objects.requireNonNull(title, "title");
            this.content = Objects.requireNonNull(content, "content");
        }

        public Builder description(String description) {
            this.description = Optional.of(description);
            return this;
        }

        public Builder viewportWidth(int viewportWidth) {
            this.viewportWidth = Optional.of(viewportWidth);
            return this;
        }

        public Builder inserter(boolean inserter) {
            this.inserter = Optional.of(inserter);
            return this;
        }

        public Builder categories(List<String> categories) {
            this.categories = Optional.of(List.copyOf(categories));
            return this;
        }

        public Builder keywords(List<String> keywords) {
            this.keywords = Optional.of(List.copyOf(keywords));
            return this;
        }

        public Builder blockTypes(List<String> blockTypes) {
            this.blockTypes = Optional.of(List.copyOf(blockTypes));
            return this;
        }

        public Builder postTypes(List<String> postTypes) {
            this.postTypes = Optional.of(List.copyOf(postTypes));
            return this;
        }

        public Builder templateTypes(List<String> templateTypes) {
            this.templateTypes = Optional.of(List.copyOf(templateTypes));
            return this;
        }

        public Builder source(String source) {
            this.source = Optional.of(source);
            return this;
        }

        public BlockPattern build() {
            return new BlockPattern(this);
        }
    }
}
