package org.quarkpress.lib.phpserialize;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.SequencedMap;

/**
 * PHP array: an ordered map whose keys are integers or byte strings. Serialized as {@code
 * a:N:{key;value;...}}.
 */
public final class PhpArray implements PhpValue {

    /** An array key: PHP allows integers and strings. */
    public sealed interface Key permits Key.Int, Key.Str {

        record Int(long value) implements Key {}

        record Str(PhpString value) implements Key {
            public Str {
                Objects.requireNonNull(value, "value");
            }
        }

        static Key of(long value) {
            return new Int(value);
        }

        /** String key encoded as UTF-8. */
        static Key of(String value) {
            return new Str(PhpString.of(value));
        }

        static Key of(PhpString value) {
            return new Str(value);
        }
    }

    private static final PhpArray EMPTY = new PhpArray(new LinkedHashMap<>());

    private final SequencedMap<Key, PhpValue> entries;

    private PhpArray(LinkedHashMap<Key, PhpValue> entries) {
        this.entries = Collections.unmodifiableSequencedMap(entries);
    }

    public static PhpArray empty() {
        return EMPTY;
    }

    /** Builds a list-shaped array with keys {@code 0..n-1}. */
    public static PhpArray of(PhpValue... values) {
        Builder b = builder();
        for (PhpValue v : values) {
            b.add(v);
        }
        return b.build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public int size() {
        return entries.size();
    }

    public SequencedMap<Key, PhpValue> entries() {
        return entries;
    }

    public PhpValue get(Key key) {
        return entries.get(key);
    }

    public PhpValue get(long key) {
        return entries.get(Key.of(key));
    }

    /** Looks up a string key by its UTF-8 bytes. */
    public PhpValue get(String key) {
        return entries.get(Key.of(key));
    }

    public List<PhpValue> values() {
        return List.copyOf(entries.values());
    }

    /** True when the keys are exactly the integers {@code 0, 1, ..., size-1} in that order. */
    public boolean isList() {
        long expected = 0;
        for (Key k : entries.keySet()) {
            if (!(k instanceof Key.Int i) || i.value() != expected) {
                return false;
            }
            expected++;
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PhpArray other && entries.equals(other.entries);
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    @Override
    public String toString() {
        return "PhpArray" + entries;
    }

    /** Mutable builder. Re-putting an existing key replaces the value but keeps its position. */
    public static final class Builder {
        private final LinkedHashMap<Key, PhpValue> map = new LinkedHashMap<>();
        private long nextIndex = 0;

        private Builder() {}

        public Builder put(Key key, PhpValue value) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            map.put(key, value);
            if (key instanceof Key.Int i && i.value() >= nextIndex) {
                nextIndex = i.value() + 1;
            }
            return this;
        }

        public Builder put(long key, PhpValue value) {
            return put(Key.of(key), value);
        }

        public Builder put(String key, PhpValue value) {
            return put(Key.of(key), value);
        }

        /** Appends with the next free integer key, like PHP's {@code $a[] = $v}. */
        public Builder add(PhpValue value) {
            return put(Key.of(nextIndex), value);
        }

        public int size() {
            return map.size();
        }

        /** True when {@code key} is already present. */
        boolean containsKey(Key key) {
            return map.containsKey(key);
        }

        public PhpArray build() {
            if (map.isEmpty()) {
                return EMPTY;
            }
            return new PhpArray(new LinkedHashMap<>(map));
        }
    }
}
