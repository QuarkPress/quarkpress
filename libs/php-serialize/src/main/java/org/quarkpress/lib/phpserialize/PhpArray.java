package org.quarkpress.lib.phpserialize;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.SequencedMap;

/**
 * PHP array: an ordered map whose keys are integers or byte strings. Serialized as {@code a:N:{key;value;...}}.
 *
 * <p>Keys follow PHP: a string that is a canonical decimal integer in the 64-bit range ({@code "5"}, {@code
 * "-5"}, {@code "0"}; not {@code "05"}, {@code "-0"}, {@code "+5"} or {@code " 5"}) is an integer key, whether
 * it comes from parsed data or from {@link Builder#put(String, PhpValue)}, and {@link #get(String)} finds an
 * integer key by its decimal text. Repeated keys keep the first position and the last value.
 */
public final class PhpArray implements PhpValue {

    /** An array key: PHP allows integers and strings. */
    public sealed interface Key permits Key.Int, Key.Str {

        record Int(long value) implements Key {}

        /** A key PHP keeps as a string. Integer text is rejected: use {@link Key#of(String)}. */
        record Str(PhpString value) implements Key {
            public Str {
                Objects.requireNonNull(value, "value");
                if (PhpKeys.integerValue(value.bytes()) != null) {
                    throw new IllegalArgumentException("\"" + value.text() + "\" is an integer key in PHP; use Key.of");
                }
            }
        }

        static Key of(long value) {
            return new Int(value);
        }

        /** The key PHP uses for this text, encoded as UTF-8. */
        static Key of(String value) {
            return of(PhpString.of(value));
        }

        /** The key PHP uses for these bytes: an integer key for canonical integer text, else a string key. */
        static Key of(PhpString value) {
            Long integer = PhpKeys.integerValue(value.bytes());
            return integer != null ? new Int(integer) : new Str(value);
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

    /** Looks up a key given as text; integer text finds the integer key, as {@code $array["2"]} does in PHP. */
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
        private boolean hasIntegerKey;
        private long maxIntegerKey;

        private Builder() {}

        public Builder put(Key key, PhpValue value) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            map.put(key, value);
            if (key instanceof Key.Int i && (!hasIntegerKey || i.value() > maxIntegerKey)) {
                maxIntegerKey = i.value();
                hasIntegerKey = true;
            }
            return this;
        }

        public Builder put(long key, PhpValue value) {
            return put(Key.of(key), value);
        }

        public Builder put(String key, PhpValue value) {
            return put(Key.of(key), value);
        }

        /**
         * Appends with the next integer key, like PHP 8.3+'s {@code $a[] = $v}: one more than the largest integer
         * key so far, even when that key is negative, or 0 when there is none.
         *
         * @throws IllegalStateException when the largest integer key is {@code Long.MAX_VALUE}
         */
        public Builder add(PhpValue value) {
            long next;
            if (!hasIntegerKey) {
                next = 0;
            } else if (maxIntegerKey == Long.MAX_VALUE) {
                throw new IllegalStateException("cannot append: the next integer key would overflow");
            } else {
                next = maxIntegerKey + 1;
            }
            return put(Key.of(next), value);
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
