package org.quarkpress.lib.phpserialize;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Pattern;

/** Recursive-descent parser over a byte array. Not thread-safe; one instance per document. */
final class PhpParser {

    private static final Pattern FLOAT_LEXEME = Pattern.compile("-?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?|-?INF|NAN");

    private final byte[] in;
    private final ParseOptions options;
    private int pos;
    private long elements;

    PhpParser(byte[] in, ParseOptions options) {
        this.in = in;
        this.options = options;
    }

    PhpValue parseDocument() {
        PhpValue value = readValue(0);
        if (pos != in.length) {
            throw fail("unexpected trailing data");
        }
        return value;
    }

    private PhpValue readValue(int depth) {
        if (depth > options.maxDepth()) {
            throw fail("nesting depth exceeds " + options.maxDepth());
        }
        byte tag = next();
        switch (tag) {
            case 'N':
                expect(';');
                return PhpNull.INSTANCE;
            case 'b':
                return readBool();
            case 'i':
                expect(':');
                return new PhpInt(readLong(';'));
            case 'd':
                expect(':');
                return readFloat();
            case 's':
                expect(':');
                byte[] bytes = readLengthPrefixedBytes(';');
                expect(';');
                return new PhpString(bytes, options.charset());
            case 'a':
                expect(':');
                return readEntries(depth).build();
            case 'O':
                return readObject(depth);
            case 'C':
                return readCustomObject();
            case 'r':
                expect(':');
                return new PhpReference(readLong(';'), PhpReference.Kind.OBJECT);
            case 'R':
                expect(':');
                return new PhpReference(readLong(';'), PhpReference.Kind.REFERENCE);
            case 'E':
                return readEnum();
            default:
                pos--;
                throw fail("unknown type tag '" + (char) tag + "'");
        }
    }

    /** Reads {@code COUNT:{key;value;...}} for arrays and object property lists. */
    private PhpArray.Builder readEntries(int depth) {
        int countStart = pos;
        long count = readLong(':');
        if (count < 0 || count > options.maxElements() - elements) {
            pos = countStart;
            throw fail("element count " + count + " exceeds limit " + options.maxElements());
        }
        elements += count;
        expect('{');
        PhpArray.Builder builder = PhpArray.builder();
        for (long i = 0; i < count; i++) {
            PhpArray.Key key = readKey();
            PhpValue value = readValue(depth + 1);
            builder.put(key, value);
        }
        expect('}');
        return builder;
    }

    private PhpArray.Key readKey() {
        int start = pos;
        byte tag = next();
        switch (tag) {
            case 'i':
                expect(':');
                return PhpArray.Key.of(readLong(';'));
            case 's':
                expect(':');
                byte[] bytes = readLengthPrefixedBytes(';');
                expect(';');
                return PhpArray.Key.of(new PhpString(bytes, options.charset()));
            default:
                pos = start;
                throw fail("array key must be an integer or a string");
        }
    }

    private PhpObject readObject(int depth) {
        expect(':');
        String className = readClassName();
        expect(':');
        return new PhpObject(className, readEntries(depth).build());
    }

    private PhpCustomObject readCustomObject() {
        expect(':');
        String className = readClassName();
        expect(':');
        int lengthStart = pos;
        long declared = readLong(':');
        if (declared < 0 || declared > options.maxStringBytes()) {
            pos = lengthStart;
            throw fail("payload length " + declared + " exceeds limit " + options.maxStringBytes());
        }
        if (declared > in.length - pos) {
            pos = lengthStart;
            throw fail("payload length " + declared + " exceeds remaining input");
        }
        expect('{');
        int start = pos;
        pos += (int) declared;
        expect('}');
        return new PhpCustomObject(className, Arrays.copyOfRange(in, start, pos - 1));
    }

    private PhpEnum readEnum() {
        expect(':');
        int start = pos;
        String text = new String(readLengthPrefixedBytes(';'), StandardCharsets.UTF_8);
        expect(';');
        int colon = text.indexOf(':');
        if (colon <= 0 || colon == text.length() - 1) {
            pos = start;
            throw fail("enum must be 'Class:Case'");
        }
        return new PhpEnum(text.substring(0, colon), text.substring(colon + 1));
    }

    /** Reads {@code LEN:"Name"}; class names are ASCII identifiers, decoded as UTF-8 to be safe. */
    private String readClassName() {
        return new String(readLengthPrefixedBytes(':'), StandardCharsets.UTF_8);
    }

    private PhpBool readBool() {
        expect(':');
        byte v = next();
        expect(';');
        return switch (v) {
            case '1' -> PhpBool.TRUE;
            case '0' -> PhpBool.FALSE;
            default -> {
                pos -= 2;
                throw fail("boolean must be 0 or 1");
            }
        };
    }

    private PhpFloat readFloat() {
        int start = pos;
        String lexeme = readAscii(';');
        if (!FLOAT_LEXEME.matcher(lexeme).matches()) {
            pos = start;
            throw fail("invalid float literal '" + lexeme + "'");
        }
        double value = switch (lexeme) {
            case "INF" -> Double.POSITIVE_INFINITY;
            case "-INF" -> Double.NEGATIVE_INFINITY;
            case "NAN" -> Double.NaN;
            default -> Double.parseDouble(lexeme);
        };
        return new PhpFloat(value, lexeme);
    }

    /**
     * Reads {@code LEN:"BYTES"} and leaves the cursor after the closing quote. {@code after} is the
     * byte that must follow the closing quote ({@code ;} for strings, {@code :} for class names);
     * it is only used to locate the end in repair mode and is not consumed.
     */
    private byte[] readLengthPrefixedBytes(char after) {
        int lengthStart = pos;
        long declared = readLong(':');
        if (options.repairLengths()) {
            return readRepairingLength(lengthStart, declared, after);
        }
        checkStringLength(declared, lengthStart);
        if (declared > in.length - pos) {
            pos = lengthStart;
            throw fail("string length " + declared + " exceeds remaining input");
        }
        expect('"');
        int start = pos;
        int end = start + (int) declared;
        pos = end;
        expect('"');
        return Arrays.copyOfRange(in, start, end);
    }

    /**
     * Lenient variant: trusts the declared length only when it lands on {@code ";} followed by a
     * plausible continuation; otherwise scans forward for the first such terminator.
     */
    private byte[] readRepairingLength(int lengthStart, long declared, char after) {
        expect('"');
        int start = pos;
        int end = -1;
        if (declared >= 0 && declared <= in.length - start && isTerminatorAt(start + (int) declared, after)) {
            end = start + (int) declared;
        } else {
            for (int i = start; i + 1 < in.length; i++) {
                if (isTerminatorAt(i, after)) {
                    end = i;
                    break;
                }
            }
        }
        if (end < 0) {
            pos = start;
            throw fail("unterminated string, length repair failed");
        }
        checkStringLength(end - start, lengthStart);
        pos = end + 1;
        return Arrays.copyOfRange(in, start, end);
    }

    /**
     * True when {@code in[i..]} is a closing quote, the expected {@code after} byte, and then end of
     * input or a plausible continuation: a value/key tag or {@code }} after {@code ";}, a digit after
     * {@code ":} (the element count or payload length that follows a class name).
     */
    private boolean isTerminatorAt(int i, char after) {
        if (i + 1 >= in.length || in[i] != '"' || in[i + 1] != after) {
            return false;
        }
        if (i + 2 >= in.length) {
            return after == ';';
        }
        byte c = in[i + 2];
        if (after == ':') {
            return c >= '0' && c <= '9';
        }
        return switch (c) {
            case '}', 'N', 'b', 'i', 'd', 's', 'a', 'O', 'C', 'r', 'R', 'E' -> true;
            default -> false;
        };
    }

    private void checkStringLength(long length, int lengthStart) {
        if (length < 0 || length > options.maxStringBytes()) {
            pos = lengthStart;
            throw fail("string length " + length + " exceeds limit " + options.maxStringBytes());
        }
    }

    private long readLong(char terminator) {
        int start = pos;
        String digits = readAscii(terminator);
        if (digits.isEmpty() || !digits.matches("-?\\d+")) {
            pos = start;
            throw fail("expected integer");
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            pos = start;
            throw fail("integer out of range: " + digits);
        }
    }

    /** Reads ASCII bytes up to (and consuming) the terminator. */
    private String readAscii(char terminator) {
        int start = pos;
        while (pos < in.length && in[pos] != terminator) {
            pos++;
        }
        if (pos >= in.length) {
            throw fail("unexpected end of input, expected '" + terminator + "'");
        }
        String s = new String(in, start, pos - start, StandardCharsets.US_ASCII);
        pos++;
        return s;
    }

    private byte next() {
        if (pos >= in.length) {
            throw fail("unexpected end of input");
        }
        return in[pos++];
    }

    private void expect(char c) {
        if (pos >= in.length) {
            throw fail("unexpected end of input, expected '" + c + "'");
        }
        if (in[pos] != c) {
            throw fail("expected '" + c + "' but found '" + (char) in[pos] + "'");
        }
        pos++;
    }

    private PhpSerializeException fail(String message) {
        return new PhpSerializeException(message, pos);
    }
}
