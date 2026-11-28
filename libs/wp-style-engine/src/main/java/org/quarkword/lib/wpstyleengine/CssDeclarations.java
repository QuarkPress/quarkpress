package org.quarkword.lib.wpstyleengine;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.quarkword.lib.wpkses.Kses;

/**
 * An ordered map of CSS properties to values, as WordPress's {@code WP_Style_Engine_CSS_Declarations} holds them.
 * Property names are sanitised the way {@code sanitize_key} does it (ASCII lowercased, then only {@code a-z},
 * {@code 0-9}, {@code _} and {@code -} kept); values are trimmed and kept raw. Values are sanitised only when the
 * declarations are rendered, through WordPress's tag stripping and {@code safecss_filter_attr}.
 *
 * <p>Instances are immutable and thread-safe. Equality covers the ordered entries and the set of important
 * properties.
 */
public final class CssDeclarations {

    private static final Kses KSES = Kses.wordPress();
    private static final CssDeclarations EMPTY = new CssDeclarations(Map.of(), Set.of());

    private final Map<String, String> declarations;
    private final Set<String> important;

    private CssDeclarations(Map<String, String> declarations, Set<String> important) {
        this.declarations = declarations;
        this.important = important;
    }

    public static CssDeclarations empty() {
        return EMPTY;
    }

    /** Each entry added in map order, without options. */
    public static CssDeclarations of(Map<String, String> declarations) {
        Objects.requireNonNull(declarations, "declarations");
        Builder builder = builder();
        declarations.forEach(builder::add);
        return builder.build();
    }

    /**
     * Declarations from a raw JSON array (an object or a list, whose keys are its indexes), entry by entry in order;
     * entries whose value is not text are dropped. Anything that is not an array, {@code null} included, gives no
     * declarations.
     */
    public static CssDeclarations fromJson(JsonNode declarations) {
        if (!Php.isArray(declarations)) {
            return EMPTY;
        }
        Builder builder = builder();
        for (Map.Entry<String, JsonNode> entry : Php.entries(declarations)) {
            if (entry.getValue().isTextual()) {
                builder.add(entry.getKey(), entry.getValue().asText());
            }
        }
        return builder.build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Sanitised property to trimmed raw value, in insertion order; unmodifiable. */
    public Map<String, String> declarations() {
        return declarations;
    }

    /**
     * The properties added with {@code !important}, in the order they took the flag (a property that keeps it keeps
     * its place, one that loses and regains it moves to the end); unmodifiable.
     */
    public Set<String> important() {
        return important;
    }

    public boolean isEmpty() {
        return declarations.isEmpty();
    }

    /** This with one declaration added without options; see {@link Builder#add(String, String)}. */
    public CssDeclarations with(String property, String value) {
        return toBuilder().add(property, value).build();
    }

    /** This with one declaration added; see {@link Builder#add(String, String, boolean)}. */
    public CssDeclarations with(String property, String value, boolean important) {
        return toBuilder().add(property, value, important).build();
    }

    /** This with every declaration of {@code other} added in order, each with its own important flag. */
    public CssDeclarations withAll(CssDeclarations other) {
        return toBuilder().addAll(other).build();
    }

    /** This without the declaration stored under exactly {@code property} (the name is not sanitised). */
    public CssDeclarations without(String property) {
        return toBuilder().remove(property).build();
    }

    /** This without each of {@code properties}, as {@link #without} removes them. */
    public CssDeclarations withoutAll(Collection<String> properties) {
        Objects.requireNonNull(properties, "properties");
        Builder builder = toBuilder();
        properties.forEach(builder::remove);
        return builder.build();
    }

    /** The declarations as one line, {@code color:red;width:1px;}, without the rejected ones. */
    public String toCss() {
        return toCss(false, 0);
    }

    /**
     * The declarations as CSS text, without the ones the sanitiser rejects. Prettified, each declaration is
     * {@code property: value;}; with {@code indent} 0 they are separated by a space, with a larger {@code indent}
     * each sits on its own line behind {@code indent} tabs. Not prettified, {@code indent} is ignored. Trailing
     * whitespace is removed.
     *
     * @throws IllegalArgumentException when prettifying with a negative {@code indent}
     */
    public String toCss(boolean prettify, int indent) {
        String indentation = prettify ? "\t".repeat(indent) : "";
        String suffix = prettify ? (indent > 0 ? "\n" : " ") : "";
        String spacer = prettify ? " " : "";
        StringBuilder css = new StringBuilder();
        for (Map.Entry<String, String> entry : declarations.entrySet()) {
            String declaration = filter(entry.getKey(), entry.getValue(), spacer, important.contains(entry.getKey()));
            // a declaration of just "0" (a bare part the sanitiser keeps) is falsy in PHP and left out
            if (!Php.isEmpty(declaration)) {
                css.append(indentation).append(declaration).append(';').append(suffix);
            }
        }
        return Php.rtrim(css.toString());
    }

    /** One declaration as the sanitiser lets it through, or {@code ""}. */
    private static String filter(String property, String value, String spacer, boolean important) {
        String stripped = Kses.stripAllTags(value, true);
        if (stripped.isEmpty()) {
            return "";
        }
        String declaration = KSES.safeCss(property + ":" + spacer + stripped);
        if (important && !declaration.isEmpty() && declaration.indexOf(';') < 0) {
            return declaration + " !important";
        }
        return declaration;
    }

    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.declarations.putAll(declarations);
        builder.important.addAll(important);
        return builder;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CssDeclarations other)
                || declarations.size() != other.declarations.size()
                || !important.equals(other.important)) {
            return false;
        }
        Iterator<Map.Entry<String, String>> mine = declarations.entrySet().iterator();
        Iterator<Map.Entry<String, String>> theirs =
                other.declarations.entrySet().iterator();
        while (mine.hasNext()) {
            if (!mine.next().equals(theirs.next())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        return 31 * declarations.hashCode() + important.hashCode();
    }

    @Override
    public String toString() {
        return "CssDeclarations" + declarations + (important.isEmpty() ? "" : " important " + important);
    }

    /** A mutable set of declarations; not thread-safe. */
    public static final class Builder {

        private final LinkedHashMap<String, String> declarations = new LinkedHashMap<>();
        private final LinkedHashSet<String> important = new LinkedHashSet<>();

        private Builder() {}

        /** Adds a declaration without options, which clears an earlier important flag of the property. */
        public Builder add(String property, String value) {
            return add(property, value, false);
        }

        /**
         * Adds a declaration. The property is sanitised; when nothing is left of it, or of the trimmed value, nothing
         * changes. An existing property keeps its position and takes the new value and flag.
         */
        public Builder add(String property, String value, boolean important) {
            Objects.requireNonNull(property, "property");
            Objects.requireNonNull(value, "value");
            String name = Keys.sanitizeKey(property);
            if (Php.isEmpty(name)) {
                return this;
            }
            String trimmed = Php.trim(value);
            if (trimmed.isEmpty()) {
                return this;
            }
            declarations.put(name, trimmed);
            if (important) {
                this.important.add(name);
            } else {
                this.important.remove(name);
            }
            return this;
        }

        /** Adds every declaration of {@code other} in order, each with its own important flag. */
        public Builder addAll(CssDeclarations other) {
            Objects.requireNonNull(other, "other");
            for (Map.Entry<String, String> entry : other.declarations.entrySet()) {
                add(entry.getKey(), entry.getValue(), other.important.contains(entry.getKey()));
            }
            return this;
        }

        /** Removes the declaration stored under exactly {@code property}; the name is not sanitised. */
        public Builder remove(String property) {
            Objects.requireNonNull(property, "property");
            declarations.remove(property);
            important.remove(property);
            return this;
        }

        public CssDeclarations build() {
            if (declarations.isEmpty()) {
                return EMPTY;
            }
            return new CssDeclarations(
                    Collections.unmodifiableMap(new LinkedHashMap<>(declarations)),
                    Collections.unmodifiableSet(new LinkedHashSet<>(important)));
        }
    }
}
