package org.quarkpress.lib.phpserialize;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.BitSet;

/**
 * Recursive-descent parser over a byte array. Not thread-safe; one instance per document.
 *
 * <p>Integer values and keys and floats may carry a leading {@code +}, as PHP's grammar allows; lengths, counts
 * and back-reference numbers are plain digits. Class names must be PHP class names: bytes from {@code A-Z a-z
 * 0-9 _ \} and 0x80-0xFF, at least one, not starting with a backslash.
 *
 * <p>Values are numbered as PHP numbers them: in document order every value takes the next slot, starting at 1,
 * except an {@code R:} reference; keys take none. {@code R:N} must name a slot allocated so far, {@code r:N} an
 * earlier slot holding an object ({@code O:}, {@code C:}, {@code E:} or another {@code r:}).
 */
final class PhpParser {

    private final byte[] in;
    private final ParseOptions options;
    private int pos;
    private long elements;
    private int slots;
    private final BitSet objectSlots = new BitSet();
    private boolean sawDuplicateKey;

    PhpParser(byte[] in, ParseOptions options) {
        this.in = in;
        this.options = options;
    }

    /** Current byte offset; used for error reporting by the facade. */
    int position() {
        return pos;
    }

    /** True when an array or object repeated a key; the later value replaced the earlier one. */
    boolean sawDuplicateKey() {
        return sawDuplicateKey;
    }

    PhpValue parseDocument() {
        PhpValue value = readValue(0);
        if (pos != in.length && !options.allowTrailingData()) {
            throw fail("unexpected trailing data");
        }
        return value;
    }

    private PhpValue readValue(int depth) {
        if (depth > options.maxDepth()) {
            throw fail("nesting depth exceeds " + options.maxDepth());
        }
        int tagAt = pos;
        byte tag = next();
        int slot = tag == 'R' ? 0 : ++slots;
        switch (tag) {
            case 'N':
                expect(';');
                return PhpNull.INSTANCE;
            case 'b':
                return readBool();
            case 'i':
                expect(':');
                return new PhpInt(readSignedInteger(';'));
            case 'd':
                expect(':');
                return readFloat();
            case 's':
                return readString();
            case 'S':
                return readEscapedString(tagAt);
            case 'a':
                expect(':');
                return readEntries(depth).build();
            case 'O':
                objectSlots.set(slot);
                return readObject(depth);
            case 'C':
                objectSlots.set(slot);
                return readCustomObject();
            case 'E':
                objectSlots.set(slot);
                return readEnum();
            case 'r':
                return readObjectHandle(slot);
            case 'R':
                return readReference();
            default:
                pos = tagAt;
                throw fail("unknown type tag '" + (char) tag + "'");
        }
    }

    /** {@code r:N;}: a copy of the handle of an earlier object. Takes a slot of its own, which holds an object. */
    private PhpReference readObjectHandle(int slot) {
        expect(':');
        int at = pos;
        long target = readUnsigned(';');
        if (target < 1 || target >= slot || !objectSlots.get((int) target)) {
            pos = at;
            throw fail("r:" + target + " does not refer to an earlier object");
        }
        objectSlots.set(slot);
        return new PhpReference(target, PhpReference.Kind.OBJECT);
    }

    /** {@code R:N;}: a PHP reference to an earlier value, possibly an enclosing one. Takes no slot. */
    private PhpReference readReference() {
        expect(':');
        int at = pos;
        long target = readUnsigned(';');
        if (target < 1 || target > slots) {
            pos = at;
            throw fail("R:" + target + " does not refer to an earlier value");
        }
        return new PhpReference(target, PhpReference.Kind.REFERENCE);
    }

    /** Reads {@code COUNT:{key;value;...}} for arrays and object property lists. */
    private PhpArray.Builder readEntries(int depth) {
        int countStart = pos;
        long count = readUnsigned(':');
        if (count > options.maxElements() - elements) {
            pos = countStart;
            throw fail("element count " + count + " exceeds limit " + options.maxElements());
        }
        elements += count;
        expect('{');
        PhpArray.Builder builder = PhpArray.builder();
        for (long i = 0; i < count; i++) {
            PhpArray.Key key = readKey();
            PhpValue value = readValue(depth + 1);
            if (builder.containsKey(key)) {
                sawDuplicateKey = true;
            }
            builder.put(key, value);
        }
        expect('}');
        return builder;
    }

    private PhpArray.Key readKey() {
        int start = pos;
        byte tag = next();
        if (tag == 'S' && options.legacyEscapedStrings()) {
            return PhpArray.Key.of(readEscapedString(start));
        }
        switch (tag) {
            case 'i':
                expect(':');
                return PhpArray.Key.of(readSignedInteger(';'));
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

    private PhpString readString() {
        expect(':');
        byte[] bytes = readLengthPrefixedBytes(';');
        expect(';');
        return new PhpString(bytes, options.charset());
    }

    /** Reads the legacy {@code S:LEN:"…";} form: LEN decoded bytes, where {@code \xx} (two hex digits) is one byte. */
    private PhpString readEscapedString(int tagAt) {
        if (!options.legacyEscapedStrings()) {
            pos = tagAt;
            throw fail("unknown type tag 'S'");
        }
        expect(':');
        int lengthStart = pos;
        long declared = readUnsigned(':');
        checkStringLength(declared, lengthStart);
        expect('"');
        if (declared > in.length - pos) {
            pos = lengthStart;
            throw fail("string length " + declared + " exceeds remaining input");
        }
        byte[] out = new byte[(int) declared];
        for (int i = 0; i < out.length; i++) {
            if (pos >= in.length) {
                throw fail("unexpected end of input in an S: string");
            }
            if (in[pos] != '\\') {
                out[i] = in[pos++];
                continue;
            }
            int high = pos + 1 < in.length ? hexValue(in[pos + 1]) : -1;
            int low = pos + 2 < in.length ? hexValue(in[pos + 2]) : -1;
            if (high < 0 || low < 0) {
                throw fail("an S: string allows a backslash only before two hex digits");
            }
            out[i] = (byte) (high << 4 | low);
            pos += 3;
        }
        expect('"');
        expect(';');
        return new PhpString(out, options.charset());
    }

    private static int hexValue(byte b) {
        if (b >= '0' && b <= '9') {
            return b - '0';
        }
        if (b >= 'a' && b <= 'f') {
            return b - 'a' + 10;
        }
        if (b >= 'A' && b <= 'F') {
            return b - 'A' + 10;
        }
        return -1;
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
        long declared = readUnsigned(':');
        if (declared > options.maxStringBytes()) {
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
        byte[] text = readLengthPrefixedBytes(';');
        expect(';');
        int colon = -1;
        for (int i = 0; i < text.length; i++) {
            if (text[i] == ':') {
                colon = i;
                break;
            }
        }
        if (colon <= 0 || colon == text.length - 1) {
            pos = start;
            throw fail("enum must be 'Class:Case'");
        }
        byte[] className = Arrays.copyOfRange(text, 0, colon);
        if (!isValidClassName(className)) {
            pos = start;
            throw fail("invalid class name in enum");
        }
        return new PhpEnum(
                new String(className, StandardCharsets.UTF_8),
                new String(text, colon + 1, text.length - colon - 1, StandardCharsets.UTF_8));
    }

    /** Reads {@code LEN:"Name"} and checks PHP's class name rule; the name is decoded as UTF-8. */
    private String readClassName() {
        int start = pos;
        byte[] name = readLengthPrefixedBytes(':');
        if (!isValidClassName(name)) {
            pos = start;
            throw fail("invalid class name");
        }
        return new String(name, StandardCharsets.UTF_8);
    }

    /** Non-empty; bytes from A-Z a-z 0-9 _ \ or 0x80-0xFF; not starting with a backslash. */
    static boolean isValidClassName(byte[] name) {
        if (name.length == 0 || name[0] == '\\') {
            return false;
        }
        for (byte b : name) {
            int c = b & 0xFF;
            boolean allowed = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_'
                    || c == '\\'
                    || c >= 0x80;
            if (!allowed) {
                return false;
            }
        }
        return true;
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
        if (!PhpFloat.isLexeme(lexeme)) {
            pos = start;
            throw fail("invalid float literal '" + lexeme + "'");
        }
        return new PhpFloat(PhpFloat.parseLexeme(lexeme), lexeme);
    }

    /**
     * Reads {@code LEN:"BYTES"} and leaves the cursor after the closing quote. {@code after} is the byte that must
     * follow the closing quote ({@code ;} for strings, {@code :} for class names); it is only used to locate the
     * end in repair mode and is not consumed.
     */
    private byte[] readLengthPrefixedBytes(char after) {
        int lengthStart = pos;
        long declared = readUnsigned(':');
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
     * Lenient variant: trusts the declared length only when it lands on {@code ";} followed by a plausible
     * continuation; otherwise scans forward for the first such terminator.
     */
    private byte[] readRepairingLength(int lengthStart, long declared, char after) {
        expect('"');
        int start = pos;
        int end = -1;
        if (declared <= in.length - start && isTerminatorAt(start + (int) declared, after)) {
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
     * True when {@code in[i..]} is a closing quote, the expected {@code after} byte, and then end of input or a
     * plausible continuation: a value/key tag or {@code }} after {@code ";}, a digit after {@code ":} (the element
     * count or payload length that follows a class name).
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
            case '}', 'N', 'b', 'i', 'd', 's', 'S', 'a', 'O', 'C', 'r', 'R', 'E' -> true;
            default -> false;
        };
    }

    private void checkStringLength(long length, int lengthStart) {
        if (length > options.maxStringBytes()) {
            pos = lengthStart;
            throw fail("string length " + length + " exceeds limit " + options.maxStringBytes());
        }
    }

    /** Reads {@code [+-]?[0-9]+} up to and including the terminator; clamps out-of-range values when allowed. */
    private long readSignedInteger(char terminator) {
        int start = pos;
        boolean negative = false;
        if (pos < in.length && (in[pos] == '-' || in[pos] == '+')) {
            negative = in[pos] == '-';
            pos++;
        }
        return readDigits(start, terminator, negative, options.clampIntegers());
    }

    /** Reads {@code [0-9]+} up to and including the terminator: lengths, counts and reference numbers. */
    private long readUnsigned(char terminator) {
        return readDigits(pos, terminator, false, false);
    }

    private long readDigits(int start, char terminator, boolean negative, boolean clamp) {
        int digitsStart = pos;
        long accumulated = 0; // kept negative so that Long.MIN_VALUE fits
        boolean overflow = false;
        while (pos < in.length && in[pos] >= '0' && in[pos] <= '9') {
            int digit = in[pos] - '0';
            if (!overflow) {
                if (accumulated < (Long.MIN_VALUE + digit) / 10) {
                    overflow = true;
                } else {
                    accumulated = accumulated * 10 - digit;
                }
            }
            pos++;
        }
        if (pos == digitsStart) {
            pos = start;
            throw fail("expected integer");
        }
        if (pos >= in.length) {
            throw fail("unexpected end of input, expected '" + terminator + "'");
        }
        if (in[pos] != terminator) {
            pos = start;
            throw fail("expected integer");
        }
        pos++;
        if (!negative && accumulated == Long.MIN_VALUE) {
            overflow = true;
        }
        if (overflow) {
            if (!clamp) {
                pos = start;
                throw fail("integer out of range");
            }
            return negative ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return negative ? accumulated : -accumulated;
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
