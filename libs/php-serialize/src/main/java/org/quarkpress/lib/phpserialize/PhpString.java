package org.quarkpress.lib.phpserialize;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * PHP string, serialized as {@code s:LEN:"BYTES";} where {@code LEN} is a byte count.
 *
 * <p>The payload is kept as raw bytes; {@code charset} is only a hint used by {@link #text()}.
 * Equality and hashing consider the bytes only.
 */
public record PhpString(byte[] bytes, Charset charset) implements PhpValue {

    public PhpString {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(charset, "charset");
        bytes = bytes.clone();
    }

    /** Creates a string from text encoded as UTF-8. */
    public static PhpString of(String text) {
        return of(text, StandardCharsets.UTF_8);
    }

    /** Creates a string from text encoded with the given charset. */
    public static PhpString of(String text, Charset charset) {
        return new PhpString(text.getBytes(charset), charset);
    }

    /** Wraps raw bytes, to be decoded as UTF-8 by {@link #text()}. */
    public static PhpString ofBytes(byte[] bytes) {
        return new PhpString(bytes, StandardCharsets.UTF_8);
    }

    /** Returns a defensive copy of the payload bytes. */
    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    public int byteLength() {
        return bytes.length;
    }

    /** Decodes the bytes with this value's charset. */
    public String text() {
        return new String(bytes, charset);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PhpString other && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "PhpString[" + text() + "]";
    }
}
