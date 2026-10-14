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
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
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
 * content. Raw source text (attributes, delimiters) and the closing form are not part of equality.
 */
public final class Block {

    private final String name;
    private final ObjectNode attributes;
    private final String rawAttributes;
    private final String rawOpener;
    private final String rawCloser;
    private final boolean explicitClose;
    private final List<Block> innerBlocks;
    private final String innerHtml;
    private final List<String> innerContent;

    /** Convenience for blocks without raw delimiter text (API-built or tests). */
    Block(
            String name,
            ObjectNode attributes,
            String rawAttributes,
            List<Block> innerBlocks,
            List<String> innerContent) {
        this(name, attributes, rawAttributes, null, null, false, innerBlocks, innerContent);
    }

    /**
     * @param rawOpener the opener (or void) delimiter exactly as parsed, or null
     * @param rawCloser the closer delimiter exactly as parsed, or null when void or unclosed
     * @param explicitClose true when the block came from an opener rather than a void delimiter; decides
     *     the form used when the raw delimiters are absent
     */
    Block(
            String name,
            ObjectNode attributes,
            String rawAttributes,
            String rawOpener,
            String rawCloser,
            boolean explicitClose,
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
        this.attributes = (ObjectNode) copyJsonValue(attributes, "$");
        this.rawAttributes = rawAttributes;
        this.rawOpener = rawOpener;
        this.rawCloser = rawCloser;
        this.explicitClose = explicitClose;
        this.innerBlocks = List.copyOf(innerBlocks);
        this.innerContent = Collections.unmodifiableList(new ArrayList<>(innerContent));
        this.innerHtml = html.toString();
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
    private static JsonNode copyJsonValue(JsonNode node, String path) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        switch (node.getNodeType()) {
            case OBJECT -> {
                ObjectNode out = f.objectNode();
                for (var e : node.properties()) {
                    out.set(e.getKey(), copyJsonValue(e.getValue(), path + "." + e.getKey()));
                }
                return out;
            }
            case ARRAY -> {
                ArrayNode out = f.arrayNode(node.size());
                for (int i = 0; i < node.size(); i++) {
                    out.add(copyJsonValue(node.get(i), path + "[" + i + "]"));
                }
                return out;
            }
            case NUMBER -> {
                return normaliseNumber(node);
            }
            case STRING, BOOLEAN, NULL -> {
                return node; // immutable value nodes
            }
            default -> throw new IllegalArgumentException("attributes must contain only JSON values: " + path);
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

    /** A deep copy of the attributes; never null, empty when the block has none. */
    public ObjectNode attributes() {
        return attributes.deepCopy();
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
        return new Block(name, attributes, null, null, null, explicitClose, innerBlocks, innerContent);
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
        return new Block(name, attributes, rawAttributes, null, null, explicitClose, innerBlocks, content);
    }

    /** Opener or void delimiter text exactly as parsed; null for API-built or modified blocks. */
    String rawOpener() {
        return rawOpener;
    }

    /** Closer delimiter text exactly as parsed; null when void, unclosed, API-built or modified. */
    String rawCloser() {
        return rawCloser;
    }

    /** True when the block came from an opener delimiter rather than a void one. */
    boolean explicitClose() {
        return explicitClose;
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
                && attributes.equals(b.attributes)
                && innerBlocks.equals(b.innerBlocks)
                && innerContent.equals(b.innerContent);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, attributes, innerBlocks, innerContent);
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
