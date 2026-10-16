package org.quarkpress.lib.wpblocks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.FloatNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One node of a parsed block document. Immutable. A block with a {@code null} name is freeform
 * (classic) content between blocks.
 *
 * <p>Invariants: the number of {@code null} entries in {@link #innerContent()} equals
 * {@link #innerBlocks()}{@code .size()}; the non-null entries concatenated equal
 * {@link #innerHtml()}; a freeform block has empty attributes, no inner blocks and exactly one
 * content chunk.
 *
 * <p>{@link #equals(Object)} compares name, attributes as JSON values, inner blocks and inner
 * content. Numbers compare by value ({@code 3} equals {@code 3.0}; WordPress writes {@code 3.0} as {@code 3}).
 * Raw source text (attributes, delimiters) and the {@linkplain #isRecoveredCopy() recovered-copy} flag are not
 * part of equality. Empty text chunks are ignored.
 *
 * <p>Attributes may hold only JSON values (object, array, string, number, boolean, null); any other
 * node, such as a POJO or binary node, is rejected with {@link IllegalArgumentException}. Number nodes
 * are normalised on construction: integral values become an int, long or big integer node (the
 * smallest that fits), a float becomes the double with the same decimal text ({@code 0.1f} becomes
 * {@code 0.1}), and a big decimal becomes a double. API-built and parsed attributes thus compare equal.
 *
 * <p>Round trip of API-built trees: {@code WpBlocks.parse(WpBlocks.serialize(blocks))} equals
 * {@code blocks} only when no two top-level freeform blocks are adjacent (they re-parse as one), no
 * freeform block is empty (it disappears), and no text, from {@link Builder#text} or a freeform block,
 * contains block delimiters (text is written as is, so {@code text("<!-- /wp:p -->x")} closes the block
 * early on re-parse).
 */
public final class Block {

    private final String name;
    private final ObjectNode attributes;
    private final boolean attributesValid;
    private final String rawAttributes;
    private final String rawOpener;
    private final String rawCloser;
    private final List<Block> innerBlocks;
    private final String innerHtml;
    private final List<String> innerContent;
    private final boolean recoveredCopy;
    private final List<String> comparableContent;

    /** Convenience for blocks without raw delimiter text (API-built or tests). */
    Block(
            String name,
            ObjectNode attributes,
            String rawAttributes,
            List<Block> innerBlocks,
            List<String> innerContent) {
        this(name, attributes, true, rawAttributes, null, null, false, innerBlocks, innerContent);
    }

    /**
     * @param rawOpener the opener (or void) delimiter exactly as parsed, or null
     * @param rawCloser the closer delimiter exactly as parsed, or null when void or unclosed
     * @param recoveredCopy true for a block that WordPress's end-of-input recovery emits a second time
     */
    Block(
            String name,
            ObjectNode attributes,
            boolean attributesValid,
            String rawAttributes,
            String rawOpener,
            String rawCloser,
            boolean recoveredCopy,
            List<Block> innerBlocks,
            List<String> innerContent) {
        Objects.requireNonNull(attributes, "attributes");
        Objects.requireNonNull(innerBlocks, "innerBlocks");
        Objects.requireNonNull(innerContent, "innerContent");
        if (name != null && !BlockNames.isValid(name)) {
            throw new IllegalArgumentException("invalid block name: " + name);
        }
        int nulls = 0;
        StringBuilder html = new StringBuilder();
        for (String chunk : innerContent) {
            if (chunk == null) {
                nulls++;
            } else {
                html.append(chunk);
            }
        }
        if (nulls != innerBlocks.size()) {
            throw new IllegalArgumentException("innerContent has " + nulls + " block positions but there are "
                    + innerBlocks.size() + " inner blocks");
        }
        if (name == null) {
            if (!attributes.isEmpty() || !innerBlocks.isEmpty() || innerContent.size() != 1) {
                throw new IllegalArgumentException(
                        "freeform block must have no attributes, no inner blocks and one chunk");
            }
        }
        this.name = name;
        ObjectNode copied;
        try {
            copied = (ObjectNode) copyJsonValue(attributes);
        } catch (NonJsonValue e) {
            throw new IllegalArgumentException("attributes must contain only JSON values: " + e.path());
        }
        this.attributes = copied;
        this.attributesValid = attributesValid;
        this.rawAttributes = rawAttributes;
        this.rawOpener = rawOpener;
        this.rawCloser = rawCloser;
        this.innerBlocks = List.copyOf(innerBlocks);
        this.innerContent = Collections.unmodifiableList(new ArrayList<>(innerContent));
        this.innerHtml = html.toString();
        this.recoveredCopy = recoveredCopy;
        this.comparableContent = this.innerContent.contains("")
                ? this.innerContent.stream()
                        .filter(c -> c == null || !c.isEmpty())
                        .toList()
                : this.innerContent;
    }

    private static final BigInteger INT_MIN = BigInteger.valueOf(Integer.MIN_VALUE);
    private static final BigInteger INT_MAX = BigInteger.valueOf(Integer.MAX_VALUE);
    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    /**
     * Deep-copies an attribute tree, normalising number nodes to the types {@code readTree} produces
     * (int, long, big integer, double) so API-built and parsed attributes compare equal as JSON values,
     * and rejecting anything that is not a JSON value (POJO, binary, missing).
     */
    private static JsonNode copyJsonValue(JsonNode node) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        switch (node.getNodeType()) {
            case OBJECT -> {
                ObjectNode out = f.objectNode();
                for (var e : node.properties()) {
                    try {
                        out.set(e.getKey(), copyJsonValue(e.getValue()));
                    } catch (NonJsonValue x) {
                        throw x.under("." + e.getKey());
                    }
                }
                return out;
            }
            case ARRAY -> {
                ArrayNode out = f.arrayNode(node.size());
                for (int i = 0; i < node.size(); i++) {
                    try {
                        out.add(copyJsonValue(node.get(i)));
                    } catch (NonJsonValue x) {
                        throw x.under("[" + i + "]");
                    }
                }
                return out;
            }
            case NUMBER -> {
                return normaliseNumber(node);
            }
            case STRING, BOOLEAN, NULL -> {
                return node; // immutable value nodes
            }
            default -> throw new NonJsonValue();
        }
    }

    /**
     * Thrown from the attribute walk when a node is not a JSON value. The path is assembled while
     * unwinding, so the walk costs nothing per node when attributes are valid.
     */
    private static final class NonJsonValue extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final ArrayDeque<String> segments = new ArrayDeque<>();

        NonJsonValue() {
            super(null, null, false, false);
        }

        NonJsonValue under(String segment) {
            segments.addFirst(segment);
            return this;
        }

        String path() {
            return "$" + String.join("", segments);
        }
    }

    private static JsonNode normaliseNumber(JsonNode node) {
        if (node instanceof DoubleNode) {
            return node;
        }
        if (node instanceof FloatNode) {
            // via decimal text, not widening: 0.1f becomes 0.1, not 0.10000000149011612
            return DoubleNode.valueOf(Double.parseDouble(Float.toString(node.floatValue())));
        }
        if (node instanceof DecimalNode) {
            return DoubleNode.valueOf(node.decimalValue().doubleValue());
        }
        if (node.isIntegralNumber()) {
            BigInteger v = node.bigIntegerValue();
            if (v.compareTo(INT_MIN) >= 0 && v.compareTo(INT_MAX) <= 0) {
                return IntNode.valueOf(v.intValue());
            }
            if (v.compareTo(LONG_MIN) >= 0 && v.compareTo(LONG_MAX) <= 0) {
                return LongNode.valueOf(v.longValue());
            }
            return BigIntegerNode.valueOf(v);
        }
        return DoubleNode.valueOf(node.doubleValue());
    }

    /** Freeform (classic) content. */
    public static Block freeform(String html) {
        return new Block(null, JsonNodeFactory.instance.objectNode(), null, List.of(), List.of(html));
    }

    /** Freeform text that WordPress's end-of-input recovery repeats from an enclosing block. */
    static Block recoveredFreeform(String html) {
        return new Block(
                null, JsonNodeFactory.instance.objectNode(), true, null, null, null, true, List.of(), List.of(html));
    }

    /** A block with no attributes and no content; serializes in the void form. */
    public static Block of(String name) {
        return of(name, JsonNodeFactory.instance.objectNode());
    }

    /** A block with attributes and no content. */
    public static Block of(String name, ObjectNode attributes) {
        return new Block(qualified(name), attributes, null, List.of(), List.of());
    }

    public static Builder builder(String name) {
        return new Builder(qualified(name));
    }

    private static String qualified(String name) {
        Objects.requireNonNull(name, "name");
        if (!BlockNames.isValid(name)) {
            throw new IllegalArgumentException("invalid block name: " + name);
        }
        return BlockNames.qualify(name);
    }

    /** Fully qualified name such as {@code core/paragraph}, or {@code null} for freeform content. */
    public String name() {
        return name;
    }

    /**
     * A deep copy of the attributes, made on every call; never null, empty when the block has none.
     * Renderers walking large trees should call it once per block and keep the result.
     */
    public ObjectNode attributes() {
        return attributes.deepCopy();
    }

    /**
     * False when the source attribute text is not JSON that PHP's {@code json_decode} accepts (malformed text,
     * an unpaired surrogate escape, nesting deeper than 511); WordPress then has {@code null} attributes, and
     * {@link #attributes()} is empty. True for API-built blocks and after {@link #withAttributes}.
     */
    public boolean attributesValid() {
        return attributesValid;
    }

    /** The attribute JSON exactly as it appeared in the source; present only on parsed, unmodified blocks. */
    public Optional<String> rawAttributes() {
        return Optional.ofNullable(rawAttributes);
    }

    public List<Block> innerBlocks() {
        return innerBlocks;
    }

    /** This block's own markup with inner block markup removed. */
    public String innerHtml() {
        return innerHtml;
    }

    /** Ordered text chunks with a {@code null} at each inner block position. */
    public List<String> innerContent() {
        return innerContent;
    }

    /**
     * True for a block that WordPress emits a second time when blocks are left open at the end of a document:
     * its text is also inside the outermost open block. {@link WpBlocks#serialize(List)} skips such blocks so the
     * round trip stays exact; {@link WpBlocks#serializeCanonical(List)} writes them, as WordPress does. Only
     * {@code serialize(List)} skips copies: {@link WpBlocks#serialize(Block)} writes one passed to it, and
     * {@link #withAttributes} and {@link #withInnerBlocks} return an ordinary block, which {@code serialize(List)}
     * writes too. The flag is not part of equality.
     */
    public boolean isRecoveredCopy() {
        return recoveredCopy;
    }

    public boolean isFreeform() {
        return name == null;
    }

    /**
     * A copy with new attributes. Raw attribute and delimiter text are dropped, so serialization
     * regenerates the delimiters from the name and the node. Not allowed on freeform blocks.
     */
    public Block withAttributes(ObjectNode attributes) {
        if (isFreeform()) {
            throw new IllegalArgumentException("freeform blocks have no attributes");
        }
        return new Block(name, attributes, true, null, null, null, false, innerBlocks, innerContent);
    }

    /**
     * A copy with new inner blocks and no text between them: {@code innerContent} becomes one
     * {@code null} per block. Attributes and raw attribute text are kept; raw delimiter text is dropped
     * because the closing form may change. Not allowed on freeform blocks.
     */
    public Block withInnerBlocks(List<Block> innerBlocks) {
        if (isFreeform()) {
            throw new IllegalArgumentException("freeform blocks have no inner blocks");
        }
        List<String> content = new ArrayList<>(innerBlocks.size());
        for (int i = 0; i < innerBlocks.size(); i++) {
            content.add(null);
        }
        return new Block(name, attributes, attributesValid, rawAttributes, null, null, false, innerBlocks, content);
    }

    /** Opener or void delimiter text exactly as parsed; null for API-built or modified blocks. */
    String rawOpener() {
        return rawOpener;
    }

    /** Closer delimiter text exactly as parsed; null when void, unclosed, API-built or modified. */
    String rawCloser() {
        return rawCloser;
    }

    /** The attribute node itself, without the copy {@link #attributes()} makes. Never modify it. */
    ObjectNode attributesView() {
        return attributes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Block b)) {
            return false;
        }
        return Objects.equals(name, b.name)
                && attributes.equals(SAME_JSON_VALUE, b.attributes)
                && innerBlocks.equals(b.innerBlocks)
                && comparableContent.equals(b.comparableContent);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, valueHash(attributes), innerBlocks, comparableContent);
    }

    /** Leaf comparison for attribute equality: numbers by value, everything else by {@code equals}. */
    private static final Comparator<JsonNode> SAME_JSON_VALUE = (a, b) -> {
        if (a.isNumber() && b.isNumber()) {
            return compareNumbers(a, b);
        }
        return a.equals(b) ? 0 : 1;
    };

    private static int compareNumbers(JsonNode a, JsonNode b) {
        if (!isFiniteNumber(a) || !isFiniteNumber(b)) {
            return Double.compare(a.doubleValue(), b.doubleValue());
        }
        return exactValue(a).compareTo(exactValue(b));
    }

    private static boolean isFiniteNumber(JsonNode n) {
        return n.isIntegralNumber() || Double.isFinite(n.doubleValue());
    }

    private static BigDecimal exactValue(JsonNode n) {
        return n.isIntegralNumber() ? new BigDecimal(n.bigIntegerValue()) : new BigDecimal(n.doubleValue());
    }

    /** A hash consistent with {@link #SAME_JSON_VALUE}: numbers hash by value, objects ignore key order. */
    private static int valueHash(JsonNode n) {
        if (n.isNumber()) {
            return isFiniteNumber(n) ? exactValue(n).stripTrailingZeros().hashCode() : Double.hashCode(n.doubleValue());
        }
        if (n.isObject()) {
            int h = 0;
            for (var e : n.properties()) {
                h += e.getKey().hashCode() ^ valueHash(e.getValue());
            }
            return h;
        }
        if (n.isArray()) {
            int h = 1;
            for (JsonNode element : n) {
                h = 31 * h + valueHash(element);
            }
            return h;
        }
        return n.hashCode();
    }

    @Override
    public String toString() {
        return "Block[" + (name == null ? "freeform" : name) + ", attrs=" + attributes + ", inner=" + innerBlocks.size()
                + ", html=" + innerHtml.length() + " chars]";
    }

    /** Builds a block's inner content in order. Consecutive text is merged; empty text is ignored. */
    public static final class Builder {
        private final String name;
        private ObjectNode attributes = JsonNodeFactory.instance.objectNode();
        private final List<Block> innerBlocks = new ArrayList<>();
        private final List<String> innerContent = new ArrayList<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder attributes(ObjectNode attributes) {
            this.attributes = Objects.requireNonNull(attributes, "attributes");
            return this;
        }

        public Builder text(String text) {
            Objects.requireNonNull(text, "text");
            if (text.isEmpty()) {
                return this;
            }
            int last = innerContent.size() - 1;
            if (last >= 0 && innerContent.get(last) != null) {
                innerContent.set(last, innerContent.get(last) + text);
            } else {
                innerContent.add(text);
            }
            return this;
        }

        public Builder inner(Block block) {
            innerBlocks.add(Objects.requireNonNull(block, "block"));
            innerContent.add(null);
            return this;
        }

        public Block build() {
            return new Block(name, attributes, null, innerBlocks, innerContent);
        }
    }
}
