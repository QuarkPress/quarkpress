package org.quarkpress.lib.wpshortcode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The attributes of one shortcode, in source order, keyed the way a PHP array is: a name made only of decimal
 * digits (optionally negative, without a leading zero, within 64 bits) is an integer index, positional values
 * take the next free index (one more than the largest index so far, or 0), and a repeated key keeps its first
 * position with its last value. Immutable and thread-safe.
 */
public final class ShortcodeAttributes {

    /** An attribute key: a (lowercase, when parsed) name or an integer index. */
    public sealed interface Key permits Key.Name, Key.Index {

        /** A named key, such as {@code id} in {@code [gallery id="5"]}. */
        record Name(String name) implements Key {
            public Name {
                Objects.requireNonNull(name, "name");
            }
        }

        /** An integer key: a positional value, or a name made only of digits. */
        record Index(long index) implements Key {}
    }

    /** One attribute. */
    public record Entry(Key key, String value) {
        public Entry {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
        }
    }

    /** Runs the attribute-defaults hooks of a tag; {@link Shortcodes} binds one to the attributes it parses. */
    @FunctionalInterface
    interface DefaultsFilter {
        Map<String, String> apply(
                String tag, Map<String, String> out, Map<String, String> defaults, ShortcodeAttributes attributes);
    }

    private static final ShortcodeAttributes EMPTY = new ShortcodeAttributes(Map.of(), null);

    private final Map<Key, String> values;
    private final DefaultsFilter filter;

    private ShortcodeAttributes(Map<Key, String> values, DefaultsFilter filter) {
        this.values = values;
        this.filter = filter;
    }

    /** No attributes. */
    public static ShortcodeAttributes empty() {
        return EMPTY;
    }

    /** The value of a name; a name made of digits reads the integer key, as in PHP. */
    public Optional<String> get(String name) {
        Objects.requireNonNull(name, "name");
        return Optional.ofNullable(values.get(keyOf(name)));
    }

    /** The value at an integer key. */
    public Optional<String> get(long index) {
        return Optional.ofNullable(values.get(new Key.Index(index)));
    }

    /** The entries with name keys, in order. */
    public Map<String, String> named() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<Key, String> e : values.entrySet()) {
            if (e.getKey() instanceof Key.Name name) {
                out.put(name.name(), e.getValue());
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** The values with integer keys (positional values and digit names), in order. */
    public List<String> positional() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<Key, String> e : values.entrySet()) {
            if (e.getKey() instanceof Key.Index) {
                out.add(e.getValue());
            }
        }
        return List.copyOf(out);
    }

    /** Every entry, in order. */
    public List<Entry> entries() {
        List<Entry> out = new ArrayList<>(values.size());
        for (Map.Entry<Key, String> e : values.entrySet()) {
            out.add(new Entry(e.getKey(), e.getValue()));
        }
        return List.copyOf(out);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public int size() {
        return values.size();
    }

    /**
     * WordPress's shortcode_atts without a tag: exactly the keys of {@code defaults}, in their order, each with
     * this collection's value when it has that key (a digit key reads the integer key) and the default otherwise.
     * Default values may be null. The result is unmodifiable.
     */
    public Map<String, String> withDefaults(Map<String, String> defaults) {
        Objects.requireNonNull(defaults, "defaults");
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> d : defaults.entrySet()) {
            String present = values.get(keyOf(d.getKey()));
            out.put(d.getKey(), present != null ? present : d.getValue());
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * shortcode_atts with a tag: as {@link #withDefaults(Map)}, then, unless the tag is empty or {@code "0"},
     * the attribute-defaults hooks registered for that tag. Only attributes handed to handlers by
     * {@link Shortcodes} know those hooks; for others this equals the plain form.
     */
    public Map<String, String> withDefaults(Map<String, String> defaults, String tag) {
        Objects.requireNonNull(tag, "tag");
        Map<String, String> out = withDefaults(defaults);
        if (filter == null || tag.isEmpty() || tag.equals("0")) {
            return out;
        }
        return filter.apply(tag, out, defaults, this);
    }

    /** The same attributes, with {@code filter} as the source of attribute-defaults hooks. */
    ShortcodeAttributes bind(DefaultsFilter filter) {
        return new ShortcodeAttributes(values, filter);
    }

    /** The PHP array key of a name: an integer for canonical decimal integers within 64 bits, else the name. */
    static Key keyOf(String name) {
        int n = name.length();
        int start = n > 0 && name.charAt(0) == '-' ? 1 : 0;
        if (start == n) {
            return new Key.Name(name);
        }
        char first = name.charAt(start);
        if (first == '0') {
            return n == 1 ? new Key.Index(0) : new Key.Name(name);
        }
        if (first < '1' || first > '9' || n - start > 19) {
            return new Key.Name(name);
        }
        for (int i = start + 1; i < n; i++) {
            char c = name.charAt(i);
            if (c < '0' || c > '9') {
                return new Key.Name(name);
            }
        }
        try {
            return new Key.Index(Long.parseLong(name));
        } catch (NumberFormatException overflow) {
            return new Key.Name(name);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ShortcodeAttributes other && entries().equals(other.entries());
    }

    @Override
    public int hashCode() {
        return entries().hashCode();
    }

    @Override
    public String toString() {
        return entries().toString();
    }

    /** Collects attributes with PHP array semantics. Not thread-safe; used by the parser. */
    static final class Builder {
        private final LinkedHashMap<Key, String> values = new LinkedHashMap<>();
        private boolean anyIndex;
        private long nextIndex;
        private boolean indexesExhausted;

        /** Sets a named value; digit names become integer keys. A repeated key keeps its position. */
        void put(String name, String value) {
            Key key = keyOf(name);
            if (key instanceof Key.Index index) {
                note(index.index());
            }
            values.put(key, value);
        }

        /** Appends a positional value at the next free integer key; dropped once key Long.MAX_VALUE is taken. */
        void append(String value) {
            if (indexesExhausted) {
                return;
            }
            long index = anyIndex ? nextIndex : 0;
            note(index);
            values.put(new Key.Index(index), value);
        }

        private void note(long index) {
            if (!anyIndex || index >= nextIndex) {
                if (index == Long.MAX_VALUE) {
                    indexesExhausted = true;
                } else {
                    nextIndex = index + 1;
                }
            }
            anyIndex = true;
        }

        /** Empties every value with a '<' that no later '>' closes (an unclosed HTML tag); keys stay. */
        void blankUnclosedTags() {
            values.replaceAll((key, value) -> tagsClosed(value) ? value : "");
        }

        private static boolean tagsClosed(String value) {
            int lt = value.indexOf('<');
            while (lt >= 0) {
                int gt = value.indexOf('>', lt + 1);
                if (gt < 0) {
                    return false;
                }
                lt = value.indexOf('<', gt + 1);
            }
            return true;
        }

        ShortcodeAttributes build() {
            return values.isEmpty()
                    ? EMPTY
                    : new ShortcodeAttributes(Collections.unmodifiableMap(new LinkedHashMap<>(values)), null);
        }
    }
}
