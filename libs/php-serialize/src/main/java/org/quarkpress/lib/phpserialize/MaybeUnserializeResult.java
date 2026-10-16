package org.quarkpress.lib.phpserialize;

import java.util.Objects;

/** The three outcomes of {@link WordPressSerialization#tryMaybeUnserialize(byte[])}. */
public sealed interface MaybeUnserializeResult {

    /** The data does not look serialized; WordPress returns it unchanged. */
    record NotSerialized(PhpString value) implements MaybeUnserializeResult {
        public NotSerialized {
            Objects.requireNonNull(value, "value");
        }
    }

    /** The data looked serialized and parsed. */
    record Parsed(PhpValue value) implements MaybeUnserializeResult {
        public Parsed {
            Objects.requireNonNull(value, "value");
        }
    }

    /** The data looked serialized but did not parse; WordPress returns {@code false}. */
    record Failed(PhpSerializeException error) implements MaybeUnserializeResult {
        public Failed {
            Objects.requireNonNull(error, "error");
        }
    }
}
