package org.quarkword.lib.wpformatting;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * {@code wptexturize}: curly quotes, apostrophes, primes, dashes, the ellipsis, the trademark and multiplication
 * signs, and the ampersand fix, as WordPress applies them to text outside tags. Text inside the no-texturize tags
 * and shortcodes stays as it is, as do comments and shortcode tags. Immutable and thread-safe; {@link #wordPress()}
 * and {@link #builder()} carry WordPress's defaults.
 */
public final class Texturize {

    /** WordPress's tags whose content is not texturized. */
    public static final List<String> DEFAULT_NO_TEXTURIZE_TAGS = List.of("pre", "code", "kbd", "style", "script", "tt");
    /** WordPress's shortcodes whose content is not texturized (only effective when {@code code} is registered). */
    public static final List<String> DEFAULT_NO_TEXTURIZE_SHORTCODES = List.of("code");

    private static final Texturize WORDPRESS = builder().build();

    private final TexturizeStrings strings;
    private final boolean enabled;
    private final Set<String> noTexturizeTags;
    private final Set<String> noTexturizeShortcodes;
    private final Set<String> registeredShortcodes;

    private Texturize(Builder builder) {
        this.strings = builder.strings;
        this.enabled = builder.enabled;
        this.noTexturizeTags = Set.copyOf(builder.noTexturizeTags);
        this.noTexturizeShortcodes = Set.copyOf(builder.noTexturizeShortcodes);
        this.registeredShortcodes = Set.copyOf(builder.registeredShortcodes);
    }

    /** English strings, enabled, WordPress's no-texturize lists and no registered shortcodes. */
    public static Texturize wordPress() {
        return WORDPRESS;
    }

    /** A builder with the settings of {@link #wordPress()}. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The texturized text. The empty text and {@code "0"} come back unchanged, as does every text when this
     * texturizer is disabled.
     */
    public String apply(String text) {
        Objects.requireNonNull(text, "text");
        if (!enabled || text.isEmpty() || text.equals("0")) {
            return text;
        }
        return Texturizer.apply(text, strings, noTexturizeTags, noTexturizeShortcodes, registeredShortcodes);
    }

    /** Settings for a {@link Texturize}; each list setter replaces the whole list. */
    public static final class Builder {

        private TexturizeStrings strings = TexturizeStrings.english();
        private boolean enabled = true;
        private Set<String> noTexturizeTags = new LinkedHashSet<>(DEFAULT_NO_TEXTURIZE_TAGS);
        private Set<String> noTexturizeShortcodes = new LinkedHashSet<>(DEFAULT_NO_TEXTURIZE_SHORTCODES);
        private Set<String> registeredShortcodes = new LinkedHashSet<>();

        private Builder() {}

        /** The quote, prime, dash and cockney strings; default {@link TexturizeStrings#english()}. */
        public Builder strings(TexturizeStrings strings) {
            this.strings = Objects.requireNonNull(strings, "strings");
            return this;
        }

        /** WordPress's {@code run_wptexturize}: false makes {@link #apply} return its input. Default true. */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /** Tag names (exact, case-sensitive) whose content is not texturized; default the WordPress list. */
        public Builder noTexturizeTags(Collection<String> tags) {
            this.noTexturizeTags = copy(tags, "tags");
            return this;
        }

        /** Shortcode names whose content is not texturized; default {@code code}. */
        public Builder noTexturizeShortcodes(Collection<String> names) {
            this.noTexturizeShortcodes = copy(names, "names");
            return this;
        }

        /**
         * The names of the registered shortcodes. Only these are recognised as shortcode tags; text in brackets
         * with any other name is texturized like the text around it. Default none.
         */
        public Builder registeredShortcodes(Collection<String> names) {
            this.registeredShortcodes = copy(names, "names");
            return this;
        }

        public Texturize build() {
            return new Texturize(this);
        }

        private static Set<String> copy(Collection<String> items, String name) {
            Objects.requireNonNull(items, name);
            Set<String> copy = new LinkedHashSet<>();
            for (String item : items) {
                copy.add(Objects.requireNonNull(item, name + " item"));
            }
            return copy;
        }
    }
}
