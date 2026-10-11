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
                byte[] bytes = readLengthPrefixedBytes();
                expect(';');
                return new PhpString(bytes, options.charset());
            default:
                pos--;
                throw fail("unknown type tag '" + (char) tag + "'");
        }
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

    /** Reads {@code LEN:"BYTES"} and leaves the cursor after the closing quote. */
    private byte[] readLengthPrefixedBytes() {
        int lengthStart = pos;
        long declared = readLong(':');
        if (declared < 0 || declared > options.maxStringBytes()) {
            pos = lengthStart;
            throw fail("string length " + declared + " exceeds limit " + options.maxStringBytes());
        }
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
