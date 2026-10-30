package org.quarkpress.restwp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A query string or form body parsed as PHP fills {@code $_GET} and {@code $_POST}: pairs split on {@code &}, names and
 * values URL-decoded ({@code +} is a space, malformed UTF-8 becomes U+FFFD), the last value of a name wins, brackets
 * build nested arrays ({@code a[]} appends at the next free integer index, {@code a[x]} sets key {@code x}), and in the
 * top-level name spaces and dots (and an unclosed {@code [}) become {@code _}. Arrays are objects keyed by their PHP
 * keys in insertion order. PHP's limits apply: 1000 pairs ({@code max_input_vars}) and 64 bracket levels
 * ({@code max_input_nesting_level}); pairs beyond them are dropped.
 */
public final class PhpQuery {

    /** PHP's default {@code max_input_vars}. */
    static final int MAX_VARS = 1000;

    /** PHP's default {@code max_input_nesting_level}. */
    static final int MAX_NESTING = 64;

    private PhpQuery() {}

    /**
     * Parses {@code raw} as the HTTP layer hands it over: a char up to U+00FF stands for one byte, a char above is
     * encoded as UTF-8.
     */
    public static ObjectNode parse(String raw) {
        return parse(raw, new long[1]);
    }

    /**
     * {@link #parse(String)}, adding to {@code scanned[0]} the characters the pair splitting looked at: each pair is
     * found in one pass over its own characters, so the count never exceeds the input's length.
     */
    static ObjectNode parse(String raw, long[] scanned) {
        ObjectNode result = RestJson.NODES.objectNode();
        if (raw == null || raw.isEmpty()) {
            return result;
        }
        Map<ObjectNode, long[]> nextIndex = new IdentityHashMap<>();
        int count = 0;
        int start = 0;
        int length = raw.length();
        while (start <= length) {
            // one pass over the pair: its end and its first '=' (searched within the pair only)
            int end = start;
            int equals = -1;
            while (end < length) {
                char c = raw.charAt(end);
                scanned[0]++;
                if (c == '&') {
                    break;
                }
                if (c == '=' && equals < 0) {
                    equals = end;
                }
                end++;
            }
            if (end > start) {
                if (++count > MAX_VARS) {
                    break;
                }
                String name;
                String value;
                if (equals >= 0) {
                    name = decode(raw, start, equals);
                    value = decode(raw, equals + 1, end);
                } else {
                    name = decode(raw, start, end);
                    value = "";
                }
                register(result, name, value, nextIndex);
            }
            start = end + 1;
        }
        return result;
    }

    /** PHP's rawurldecode: {@code %XX} as a byte, other text (a {@code +} included) as is. */
    public static String rawDecode(String raw) {
        return decode(raw, 0, raw.length(), false);
    }

    static String decode(String raw, int from, int to) {
        return decode(raw, from, to, true);
    }

    /** PHP's urldecode of {@code raw[from, to)}: {@code +} as space (with {@code plus}), {@code %XX} as a byte. */
    private static String decode(String raw, int from, int to, boolean plus) {
        boolean plain = true;
        for (int i = from; i < to && plain; i++) {
            char c = raw.charAt(i);
            plain = (c != '+' || !plus) && c != '%' && c < 0x80;
        }
        if (plain) {
            return raw.substring(from, to);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(to - from);
        for (int i = from; i < to; i++) {
            char c = raw.charAt(i);
            if (c == '+' && plus) {
                bytes.write(' ');
            } else if (c == '%' && i + 2 < to && hex(raw.charAt(i + 1)) >= 0 && hex(raw.charAt(i + 2)) >= 0) {
                bytes.write(hex(raw.charAt(i + 1)) * 16 + hex(raw.charAt(i + 2)));
                i += 2;
            } else if (c <= 0xFF) {
                // the HTTP layer hands a request line's bytes over one char per byte
                bytes.write(c);
            } else {
                int end = Character.isHighSurrogate(c) && i + 1 < to && Character.isLowSurrogate(raw.charAt(i + 1))
                        ? i + 2
                        : i + 1;
                byte[] encoded = raw.substring(i, end).getBytes(StandardCharsets.UTF_8);
                bytes.write(encoded, 0, encoded.length);
                i = end - 1;
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    /** PHP's registration of one variable {@code name} (decoded) with {@code value}. */
    private static void register(ObjectNode result, String name, String value, Map<ObjectNode, long[]> nextIndex) {
        int nul = name.indexOf('\0');
        if (nul >= 0) {
            name = name.substring(0, nul);
        }
        int start = 0;
        while (start < name.length() && name.charAt(start) == ' ') {
            start++;
        }
        String var = name.substring(start);
        int open = var.indexOf('[');
        if (var.isEmpty() || open == 0) {
            // no name before the first bracket
            return;
        }
        boolean array = open >= 0 && var.indexOf(']', open + 1) >= 0;
        StringBuilder base = new StringBuilder(var.length());
        int baseEnd = array ? open : var.length();
        for (int i = 0; i < baseEnd; i++) {
            char c = var.charAt(i);
            base.append(c == ' ' || c == '.' || c == '[' ? '_' : c);
        }
        if (base.isEmpty()) {
            return;
        }
        if (!array) {
            result.set(base.toString(), TextNode.valueOf(value));
            return;
        }
        // the indexes: [x] keys, [] appends; text after a closing bracket that is not '[' is ignored, and so is an
        // index whose bracket never closes
        List<String> indexes = new ArrayList<>();
        int at = open;
        while (at < var.length() && var.charAt(at) == '[') {
            int close = var.indexOf(']', at + 1);
            if (close < 0) {
                break;
            }
            // [] and [ ] (one white-space character) append; any other text is the key, white space included
            boolean append = close == at + 1 || (close == at + 2 && space(var.charAt(at + 1)));
            indexes.add(append ? null : var.substring(at + 1, close));
            at = close + 1;
        }
        if (indexes.size() > MAX_NESTING) {
            return;
        }
        ObjectNode container = result;
        String key = base.toString();
        for (String index : indexes) {
            ObjectNode child = container.get(key) instanceof ObjectNode existing ? existing : null;
            if (child == null) {
                child = RestJson.NODES.objectNode();
                put(container, key, child, nextIndex);
            }
            container = child;
            if (index == null) {
                key = append(container, nextIndex);
                if (key == null) {
                    return;
                }
            } else {
                key = index;
            }
        }
        put(container, key, TextNode.valueOf(value), nextIndex);
    }

    private static boolean space(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r';
    }

    private static void put(ObjectNode container, String key, JsonNode value, Map<ObjectNode, long[]> nextIndex) {
        container.set(key, value);
        Long integer = integerKey(key);
        if (integer != null) {
            long[] next = nextIndex.computeIfAbsent(container, ignored -> new long[] {Long.MIN_VALUE, 0});
            // next[1]: 0 = unset, 1 = set, 2 = exhausted (after the largest integer key)
            if (next[1] == 0 || (next[1] == 1 && integer >= next[0])) {
                if (integer == Long.MAX_VALUE) {
                    next[1] = 2;
                } else {
                    next[0] = integer + 1;
                    next[1] = 1;
                }
            }
        }
    }

    /** The key {@code []} appends under, or null when the array has no next index left. */
    private static String append(ObjectNode container, Map<ObjectNode, long[]> nextIndex) {
        long[] next = nextIndex.computeIfAbsent(container, ignored -> new long[] {Long.MIN_VALUE, 0});
        if (next[1] == 2) {
            return null;
        }
        long index = next[1] == 0 ? 0 : next[0];
        return Long.toString(index);
    }

    /** A PHP integer array key: a canonical decimal integer in the 64-bit range ({@code -0} and {@code 05} are not). */
    static Long integerKey(String key) {
        int length = key.length();
        if (length == 0 || length > 20) {
            return null;
        }
        int i = key.charAt(0) == '-' ? 1 : 0;
        if (i == length || (key.charAt(i) == '0' && (length > i + 1 || i == 1))) {
            return null;
        }
        for (int j = i; j < length; j++) {
            char c = key.charAt(j);
            if (c < '0' || c > '9') {
                return null;
            }
        }
        try {
            return Long.parseLong(key);
        } catch (NumberFormatException outOfRange) {
            return null;
        }
    }
}
