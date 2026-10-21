package org.quarkpress.posts.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Decides whether a difference between WordPress's and QuarkPress's filtered text is only one that lib-wp-kses and
 * lib-wp-blocks document: block attribute JSON written after the PHP round trip (big integers become floats, {@code {}}
 * and numeric-key objects become lists, and a number that overflows to infinity removes all attributes), and block
 * delimiters that lib-wp-kses neutralizes ({@code &lt;!--} for {@code <!--}, {@code --&gt;} for {@code -->}). Both
 * texts are cut into plain text and block delimiters; the plain text must be equal byte for byte. A delimiter whose
 * attributes WordPress dropped is accepted only for an input holding an overflowing number and only when QuarkPress's
 * attributes hold such a number or the {@code null} written for one. Attributes count as rewritten only when they
 * differ as values, not when they differ only in how the text is written or in what kses does to a comment.
 */
final class BlockDifferences {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** What accepting a difference relied on. */
    record Verdict(boolean accepted, boolean rewrittenAttributes, boolean neutralizedDelimiters) {
        static final Verdict REJECTED = new Verdict(false, false, false);
    }

    /** One block delimiter: its name, whether it closes or is void, and its attribute JSON (null when none). */
    private record Delimiter(String name, boolean closer, boolean voidBlock, String json, boolean neutralized) {}

    private BlockDifferences() {}

    static Verdict check(String wordpress, String quarkpress, boolean allowNeutralized, boolean allowOverflow) {
        List<Object> w = cut(wordpress, allowNeutralized);
        List<Object> q = cut(quarkpress, allowNeutralized);
        if (w.size() != q.size()) {
            return Verdict.REJECTED;
        }
        boolean rewritten = false;
        boolean neutralized = false;
        for (int i = 0; i < w.size(); i++) {
            Object a = w.get(i);
            Object b = q.get(i);
            if (a instanceof String x) {
                if (!x.equals(b)) {
                    return Verdict.REJECTED;
                }
            } else if (a instanceof Delimiter x && b instanceof Delimiter y) {
                if (!x.name().equals(y.name()) || x.closer() != y.closer() || x.voidBlock() != y.voidBlock()) {
                    return Verdict.REJECTED;
                }
                if (x.neutralized() && !y.neutralized()) {
                    return Verdict.REJECTED;
                }
                boolean newlyNeutral = y.neutralized() && !x.neutralized();
                neutralized |= newlyNeutral;
                if (x.neutralized() && !Objects.equals(x.json(), y.json())) {
                    return Verdict.REJECTED;
                }
                if (x.json() == null ? y.json() != null : !sameJson(x.json(), y.json(), newlyNeutral)) {
                    if (!(allowOverflow && x.json() == null && overflowWritten(y.json()))) {
                        return Verdict.REJECTED;
                    }
                }
                rewritten |= differentValues(x.json(), y.json());
            } else {
                return Verdict.REJECTED;
            }
        }
        return new Verdict(true, rewritten, neutralized);
    }

    /** Plain text strings and delimiters, in order. */
    private static List<Object> cut(String text, boolean allowNeutralized) {
        List<Object> parts = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            boolean live = text.startsWith("<!--", i);
            boolean neutral = allowNeutralized && text.startsWith("&lt;!--", i);
            if (live || neutral) {
                int[] end = new int[1];
                Delimiter d = read(text, i + (live ? 4 : 7), neutral, allowNeutralized, end);
                if (d != null) {
                    if (!plain.isEmpty()) {
                        parts.add(plain.toString());
                        plain.setLength(0);
                    }
                    parts.add(d);
                    i = end[0];
                    continue;
                }
            }
            plain.append(text.charAt(i));
            i++;
        }
        if (!plain.isEmpty()) {
            parts.add(plain.toString());
        }
        return parts;
    }

    private static Delimiter read(String text, int from, boolean neutral, boolean lenient, int[] end) {
        int i = skipSpace(text, from);
        boolean closer = i < text.length() && text.charAt(i) == '/';
        if (closer) {
            i++;
        }
        if (!text.startsWith("wp:", i)) {
            return null;
        }
        int nameStart = i;
        i += 3;
        while (i < text.length() && (Character.isLetterOrDigit(text.charAt(i)) || "_/-".indexOf(text.charAt(i)) >= 0)) {
            i++;
        }
        String name = text.substring(nameStart, i);
        int afterName = i;
        i = skipSpace(text, i);
        if (i == afterName) {
            return null;
        }
        String json = null;
        if (i < text.length() && (text.charAt(i) == '{' || text.charAt(i) == '[')) {
            char last = text.charAt(i) == '{' ? '}' : ']';
            int brace = text.indexOf(last, i);
            while (brace >= 0) {
                int after = skipSpace(text, brace + 1);
                if (after > brace + 1
                        && (closeLength(text, after, neutral) > 0
                                || closeLength(text, after + 1, neutral) > 0 && text.charAt(after) == '/')) {
                    break;
                }
                brace = text.indexOf(last, brace + 1);
            }
            if (brace < 0 || brace > nextComment(text, i)) {
                return null;
            }
            json = text.substring(i, brace + 1);
            if (!lenient && !parses(json)) {
                return null;
            }
            i = skipSpace(text, brace + 1);
        }
        boolean voidBlock = false;
        if (i < text.length() && text.charAt(i) == '/') {
            voidBlock = true;
            i++;
        }
        int closeLength = closeLength(text, i, neutral);
        if (closeLength < 0) {
            return null;
        }
        end[0] = i + closeLength;
        return new Delimiter(name, closer, voidBlock, json, neutral);
    }

    /** Where the next comment opener, live or neutralized, starts; attribute JSON never reaches past it. */
    private static int nextComment(String text, int from) {
        int live = text.indexOf("<!--", from);
        int neutral = text.indexOf("&lt;!--", from);
        int next = live < 0 ? neutral : neutral < 0 ? live : Math.min(live, neutral);
        return next < 0 ? Integer.MAX_VALUE : next;
    }

    /** The length of the comment end at {@code at}: {@code -->}, or for a neutralized delimiter {@code --&gt;}. */
    private static int closeLength(String text, int at, boolean neutral) {
        if (text.startsWith("-->", at)) {
            return 3;
        }
        return neutral && text.startsWith("--&gt;", at) ? 6 : -1;
    }

    private static int skipSpace(String text, int from) {
        int i = from;
        while (i < text.length() && " \t\n\r\f".indexOf(text.charAt(i)) >= 0) {
            i++;
        }
        return i;
    }

    /**
     * The same attributes. A delimiter that WordPress leaves live is a comment to kses, which removes the backslash
     * before a double quote and shortens runs of dashes; QuarkPress's neutralized copy is text and keeps both. For
     * such a delimiter, a difference that disappears once those two changes are made is accepted.
     */
    private static boolean sameJson(String wordpress, String quarkpress, boolean newlyNeutral) {
        if (wordpress.equals(quarkpress)) {
            return true;
        }
        if (parses(wordpress) && parses(quarkpress) && canonical(wordpress).equals(canonical(quarkpress))) {
            return true;
        }
        return newlyNeutral && commentText(wordpress).equals(commentText(quarkpress));
    }

    /** What kses's comment handling does to the text: {@code \"} becomes {@code "}, and dash runs one dash. */
    private static String commentText(String json) {
        String text = json.replace("\\\"", "\"");
        while (text.contains("--")) {
            text = text.replace("--", "-");
        }
        return text;
    }

    /**
     * Whether attributes that WordPress dropped are the ones lib-wp-blocks writes for an overflowing number: they hold
     * a number that overflows to infinity (an integer kept exact) or the {@code null} written for one.
     */
    private static boolean overflowWritten(String json) {
        try {
            JsonNode tree = MAPPER.readTree(json);
            return infinite(tree) || holdsNull(tree);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean holdsNull(JsonNode node) {
        if (node.isNull()) {
            return true;
        }
        for (JsonNode child : node) {
            if (holdsNull(child)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether QuarkPress wrote other attribute values than WordPress: attributes WordPress dropped, or attributes that
     * both parse and differ as values. Text that differs only in spacing or escaping, or only as kses changes a
     * comment, is not a rewrite.
     */
    private static boolean differentValues(String wordpress, String quarkpress) {
        if (Objects.equals(wordpress, quarkpress)) {
            return false;
        }
        if (wordpress == null || quarkpress == null) {
            return true;
        }
        try {
            return !MAPPER.readTree(wordpress).equals(MAPPER.readTree(quarkpress));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean parses(String json) {
        try {
            MAPPER.readTree(json);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Attribute JSON as PHP's decode and encode give it back, as text. */
    private static String canonical(String json) {
        try {
            JsonNode tree = MAPPER.readTree(json);
            return infinite(tree) ? "<infinite>" : roundTrip(tree).toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean infinite(JsonNode node) {
        if (node.isFloatingPointNumber()) {
            return !Double.isFinite(node.doubleValue());
        }
        if (node.isBigInteger()) {
            return !Double.isFinite(node.doubleValue());
        }
        for (JsonNode child : node) {
            if (infinite(child)) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode roundTrip(JsonNode node) {
        if (node instanceof ObjectNode object) {
            if (object.isEmpty() || sequential(object)) {
                ArrayNode list = JsonNodeFactory.instance.arrayNode();
                object.forEach(value -> list.add(roundTrip(value)));
                return list;
            }
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            for (Iterator<Map.Entry<String, JsonNode>> it = object.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                copy.set(entry.getKey(), roundTrip(entry.getValue()));
            }
            return copy;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            array.forEach(value -> copy.add(roundTrip(value)));
            return copy;
        }
        if (node.isBigInteger() && node.bigIntegerValue().bitLength() > 63) {
            return JsonNodeFactory.instance.numberNode(node.doubleValue());
        }
        return node;
    }

    private static boolean sequential(ObjectNode object) {
        int expected = 0;
        for (Iterator<String> names = object.fieldNames(); names.hasNext(); ) {
            if (!names.next().equals(Integer.toString(expected))) {
                return false;
            }
            expected++;
        }
        return true;
    }
}
