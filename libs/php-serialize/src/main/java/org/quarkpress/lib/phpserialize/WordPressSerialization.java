package org.quarkpress.lib.phpserialize;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * WordPress's helpers around PHP serialization, with WordPress's exact answers: {@code is_serialized}, {@code
 * is_serialized_string}, {@code maybe_serialize} and {@code maybe_unserialize}. They decide what an option or
 * meta value looks like in the database, so code that shares a database with WordPress must agree with them.
 *
 * <p>The rules look at bytes. {@code byte[]} methods use them directly; {@code String} methods encode the text as
 * UTF-8 first. Text from a column in another encoding must be passed as its original bytes.
 */
public final class WordPressSerialization {

    private WordPressSerialization() {}

    /** {@code is_serialized($data)}: the strict check WordPress uses before unserializing. */
    public static boolean isSerialized(String data) {
        return isSerialized(data, true);
    }

    /**
     * {@code is_serialized($data, $strict)}. The loose form ({@code strict == false}) is what {@code
     * maybe_serialize} uses to decide whether a string must be serialized again.
     */
    public static boolean isSerialized(String data, boolean strict) {
        if (data == null) {
            return false;
        }
        String s = phpTrim(data);
        if (s.equals("N;")) {
            return true;
        }
        if (s.length() < 4 || s.charAt(1) != ':') {
            return false;
        }
        if (strict) {
            char last = s.charAt(s.length() - 1);
            if (last != ';' && last != '}') {
                return false;
            }
        } else {
            int semicolon = s.indexOf(';');
            int brace = s.indexOf('}');
            if (semicolon < 0 && brace < 0) {
                return false;
            }
            if ((semicolon >= 0 && semicolon < 3) || (brace >= 0 && brace < 4)) {
                return false;
            }
        }
        return switch (s.charAt(0)) {
            case 's' -> (strict ? s.charAt(s.length() - 2) == '"' : s.indexOf('"') >= 0) && hasLengthPrefix(s);
            case 'a', 'O', 'E' -> hasLengthPrefix(s);
            case 'b', 'i', 'd' -> hasScalarBody(s, strict);
            default -> false;
        };
    }

    /** {@code is_serialized_string($data)}. */
    public static boolean isSerializedString(String data) {
        if (data == null) {
            return false;
        }
        String s = phpTrim(data);
        return s.length() >= 4
                && s.charAt(1) == ':'
                && s.charAt(s.length() - 1) == ';'
                && s.charAt(0) == 's'
                && s.charAt(s.length() - 2) == '"';
    }

    /**
     * {@code maybe_serialize($data)}, returned as the bytes the database column receives. Arrays and objects are
     * serialized; a string that looks serialized (loose rule) is serialized again, so that reading it back gives
     * the same string; other scalars become their text ({@code 5}, {@code 1} for true, empty for false and null,
     * a float as PHP's {@code (string)} cast writes it).
     *
     * @throws IllegalArgumentException for a lone {@link PhpReference}, which has no value of its own
     */
    public static byte[] maybeSerialize(PhpValue value) {
        Objects.requireNonNull(value, "value");
        return switch (value) {
            case PhpArray a -> PhpSerialize.serialize(a);
            case PhpObject o -> PhpSerialize.serialize(o);
            case PhpCustomObject c -> PhpSerialize.serialize(c);
            case PhpEnum e -> PhpSerialize.serialize(e);
            case PhpString s -> isSerialized(latin1(s.bytes()), false) ? PhpSerialize.serialize(s) : s.bytes();
            case PhpInt i -> ascii(Long.toString(i.value()));
            case PhpFloat f -> ascii(PhpFloatFormat.castToString(f.value()));
            case PhpBool b -> b.value() ? ascii("1") : new byte[0];
            case PhpNull n -> new byte[0];
            case PhpReference r -> throw new IllegalArgumentException("a reference has no value of its own to store");
        };
    }

    /** {@code maybe_unserialize($data)} for text, which is encoded as UTF-8 first. */
    public static PhpValue maybeUnserialize(String data) {
        return maybeUnserialize(Objects.requireNonNull(data, "data").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * {@code maybe_unserialize($data)}: the data unchanged (as a {@link PhpString}) when it does not look
     * serialized; the parsed value when it does; {@link PhpBool#FALSE} when it looks serialized but does not
     * parse, as WordPress returns {@code false}. Parsing uses {@link ParseOptions#PHP_COMPATIBLE} on the data
     * trimmed like PHP's {@code trim()}.
     */
    public static PhpValue maybeUnserialize(byte[] data) {
        return switch (tryMaybeUnserialize(data)) {
            case MaybeUnserializeResult.NotSerialized n -> n.value();
            case MaybeUnserializeResult.Parsed p -> p.value();
            case MaybeUnserializeResult.Failed f -> PhpBool.FALSE;
        };
    }

    /** Like {@link #maybeUnserialize(String)}, but tells the three outcomes apart. */
    public static MaybeUnserializeResult tryMaybeUnserialize(String data) {
        return tryMaybeUnserialize(Objects.requireNonNull(data, "data").getBytes(StandardCharsets.UTF_8));
    }

    /** Like {@link #maybeUnserialize(byte[])}, but tells the three outcomes apart. */
    public static MaybeUnserializeResult tryMaybeUnserialize(byte[] data) {
        Objects.requireNonNull(data, "data");
        if (!isSerialized(latin1(data))) {
            return new MaybeUnserializeResult.NotSerialized(PhpString.ofBytes(data));
        }
        try {
            return new MaybeUnserializeResult.Parsed(PhpSerialize.parse(phpTrim(data), ParseOptions.PHP_COMPATIBLE));
        } catch (PhpSerializeException e) {
            return new MaybeUnserializeResult.Failed(e);
        }
    }

    /** True for the bytes PHP's {@code trim()} removes by default. */
    private static boolean isPhpTrimmed(int c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0 || c == 0x0B;
    }

    private static String phpTrim(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isPhpTrimmed(s.charAt(start))) {
            start++;
        }
        while (end > start && isPhpTrimmed(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    private static byte[] phpTrim(byte[] b) {
        int start = 0;
        int end = b.length;
        while (start < end && isPhpTrimmed(b[start] & 0xFF)) {
            start++;
        }
        while (end > start && isPhpTrimmed(b[end - 1] & 0xFF)) {
            end--;
        }
        return Arrays.copyOfRange(b, start, end);
    }

    /** After {@code X:}: one or more ASCII digits, then {@code :}. */
    private static boolean hasLengthPrefix(String s) {
        int i = 2;
        while (i < s.length() && isDigit(s.charAt(i))) {
            i++;
        }
        return i > 2 && i < s.length() && s.charAt(i) == ':';
    }

    /** After {@code X:}: one or more of {@code 0-9 . E + -}, then {@code ;}, which strict mode wants last. */
    private static boolean hasScalarBody(String s, boolean strict) {
        int i = 2;
        while (i < s.length() && isScalarChar(s.charAt(i))) {
            i++;
        }
        if (i == 2 || i >= s.length() || s.charAt(i) != ';') {
            return false;
        }
        return !strict || i == s.length() - 1;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isScalarChar(char c) {
        return isDigit(c) || c == '.' || c == 'E' || c == '+' || c == '-';
    }

    private static String latin1(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
