package org.quarkpress.lib.phpserialize;

/**
 * PHP float, serialized as {@code d:1.5;}.
 *
 * <p>{@code raw} keeps the exact lexeme read by the parser so that writing the value back is
 * byte-identical. It is {@code null} for values created in Java. Equality considers only {@code
 * value} (with {@code NaN} equal to {@code NaN} and {@code 0.0} distinct from {@code -0.0}).
 */
public record PhpFloat(double value, String raw) implements PhpValue {

    public PhpFloat(double value) {
        this(value, null);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PhpFloat other && Double.compare(value, other.value) == 0;
    }

    @Override
    public int hashCode() {
        return Double.hashCode(value);
    }
}
