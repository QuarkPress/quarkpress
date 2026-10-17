package org.quarkpress.persistence.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * What an option value may hold, so that PostgreSQL and MySQL store it the same way and PHP could hold it: no JSON
 * null, no unpaired surrogate in strings or keys (they have no UTF-8 form), integers within 64 bits, finite numbers
 * within the range of a double (neither too large nor, unless zero, too small), at most 64 nested arrays or objects.
 * U+0000 is allowed.
 *
 * <p>The checked copy holds numbers in the form they are read back from the database: integers as int or long nodes,
 * other numbers as {@code BigDecimal} nodes (a double as its shortest decimal, {@code 0.1} rather than its binary
 * expansion), so the copy equals the value that is later read.
 */
public final class JsonValues {

    public static final int MAX_DEPTH = 64;

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final BigInteger MIN_LONG = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger MAX_LONG = BigInteger.valueOf(Long.MAX_VALUE);

    private JsonValues() {}

    /** Checks {@code value} and returns a copy of it, with numbers in their stored form. */
    public static JsonNode requireStorable(JsonNode value) {
        Objects.requireNonNull(value, "value");
        if (value.isNull() || value.isMissingNode()) {
            throw new IllegalArgumentException("an option value cannot be JSON null; delete the option instead");
        }
        return copy(value, 0);
    }

    /**
     * JSON equality where numbers compare by value ({@code 1}, {@code 1L} and {@code 1.0} are the same; a double or
     * float counts as its shortest decimal) and object keys compare in order, as PHP compares arrays:
     * {@code {"a":1,"b":2}} and {@code {"b":2,"a":1}} differ. Values nested deeper than {@link #MAX_DEPTH} count as
     * different.
     */
    public static boolean sameValue(JsonNode a, JsonNode b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        return same(a, b, 0);
    }

    private static boolean same(JsonNode a, JsonNode b, int depth) {
        if (depth > MAX_DEPTH) {
            // Deeper than any storable value: such a value (written around these rules) counts as different.
            return false;
        }
        if (a.isNumber() && b.isNumber()) {
            if (!isFinite(a) || !isFinite(b)) {
                return a.equals(b);
            }
            return decimalOf(a).compareTo(decimalOf(b)) == 0;
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!same(a.get(i), b.get(i), depth + 1)) {
                    return false;
                }
            }
            return true;
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) {
                return false;
            }
            Iterator<Map.Entry<String, JsonNode>> left = a.properties().iterator();
            Iterator<Map.Entry<String, JsonNode>> right = b.properties().iterator();
            while (left.hasNext()) {
                Map.Entry<String, JsonNode> l = left.next();
                Map.Entry<String, JsonNode> r = right.next();
                if (!l.getKey().equals(r.getKey()) || !same(l.getValue(), r.getValue(), depth + 1)) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    /** A copy of a value read from a column: a missing value (SQL NULL) reads as JSON null. */
    public static JsonNode copyOfStored(JsonNode stored) {
        return stored == null ? NullNode.getInstance() : stored.deepCopy();
    }

    private static JsonNode copy(JsonNode node, int depth) {
        if (node.isContainerNode()) {
            if (depth + 1 > MAX_DEPTH) {
                throw new IllegalArgumentException("JSON values may nest at most " + MAX_DEPTH + " arrays or objects");
            }
            if (node.isObject()) {
                ObjectNode object = NODES.objectNode();
                for (Map.Entry<String, JsonNode> field : node.properties()) {
                    checkText(field.getKey());
                    object.set(field.getKey(), copy(field.getValue(), depth + 1));
                }
                return object;
            }
            ArrayNode array = NODES.arrayNode(node.size());
            for (JsonNode child : node) {
                array.add(copy(child, depth + 1));
            }
            return array;
        }
        if (node.isTextual()) {
            checkText(node.textValue());
            return node;
        }
        if (node.isNumber()) {
            return number(node);
        }
        if (node.isBoolean()) {
            return node;
        }
        if (node.isNull() || node.isMissingNode()) {
            // JSON null inside an array or object is plain JSON (PHP null); only the whole value cannot be null.
            return NullNode.getInstance();
        }
        throw new IllegalArgumentException("only plain JSON values can be stored, not " + node.getNodeType());
    }

    private static JsonNode number(JsonNode node) {
        if (node.isIntegralNumber()) {
            return integer(node.bigIntegerValue());
        }
        if (!isFinite(node)) {
            throw new IllegalArgumentException(
                    "numbers in option values must be finite and within the range of a double");
        }
        BigDecimal decimal = decimalOf(node);
        // As a double the number must neither overflow nor, unless it is zero, round to zero.
        double asDouble = decimal.doubleValue();
        if (Double.isInfinite(asDouble) || (asDouble == 0.0 && decimal.signum() != 0)) {
            throw new IllegalArgumentException(
                    "numbers in option values must be finite and within the range of a double");
        }
        // Written without a fraction or exponent, a decimal reads back as an integer.
        return decimal.scale() == 0 ? integer(decimal.unscaledValue()) : DecimalNode.valueOf(decimal);
    }

    private static boolean isFinite(JsonNode number) {
        return !(number.isDouble() || number.isFloat()) || Double.isFinite(number.doubleValue());
    }

    /** The exact value of a number; a double or float counts as its shortest decimal ({@code 0.1}, not its expansion). */
    private static BigDecimal decimalOf(JsonNode number) {
        if (number.isFloat()) {
            return new BigDecimal(Float.toString(number.floatValue()));
        }
        if (number.isDouble()) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return number.decimalValue();
    }

    private static JsonNode integer(BigInteger value) {
        if (value.compareTo(MIN_LONG) < 0 || value.compareTo(MAX_LONG) > 0) {
            throw new IllegalArgumentException("integers in option values must fit in 64 bits, as PHP integers do");
        }
        long l = value.longValue();
        return l == (int) l ? IntNode.valueOf((int) l) : LongNode.valueOf(l);
    }

    private static void checkText(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    throw new IllegalArgumentException("JSON strings and keys cannot contain unpaired surrogates");
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("JSON strings and keys cannot contain unpaired surrogates");
            }
        }
    }
}
