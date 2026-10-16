package org.quarkpress.lib.wpblocks;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.quarkpress.lib.wpblocks.DelimiterScanner.Delimiter;

/**
 * Single-pass scanner with an explicit frame stack that builds the tree WordPress's {@code parse_blocks} builds.
 *
 * <p>Text is assigned lazily: an open block takes the text after its last assigned offset only when a child
 * starts or the block closes, and text before a top-level opener waits until that block is emitted. That decides
 * the corner cases: a closer with nothing open ends parsing and the rest of the document becomes one freeform
 * block; blocks left open at the end are emitted at top level, innermost first, each after its leading text,
 * while the outermost keeps the rest of the document as text; a nested block that closes on empty text gets an
 * empty chunk.
 *
 * <p>Each character still has exactly one place that {@link WpBlocks#serialize} writes back: blocks repeated by
 * the end-of-input recovery are marked as {@linkplain Block#isRecoveredCopy() recovered copies} and skipped there.
 */
final class BlockParser {

    private final String s;
    private final ParseOptions options;
    private final List<Block> top = new ArrayList<>();
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private final AttributeEnds ends;
    private int blockCount;

    /** An open block. */
    private static final class Frame {
        final Delimiter opener;
        final String rawOpener;
        final int leadingStart; // start of the text before the opener that nothing has taken, or -1
        final ObjectNode attributes;
        final boolean attributesValid;
        final List<Block> innerBlocks = new ArrayList<>();
        final List<String> innerContent = new ArrayList<>();
        int contentFrom; // first offset of this block's content not yet assigned

        Frame(String source, Delimiter opener, int leadingStart) {
            this.opener = opener;
            this.rawOpener = source.substring(opener.start(), opener.end());
            this.leadingStart = leadingStart;
            ObjectNode parsed = parseAttributes(opener);
            this.attributesValid = parsed != null;
            this.attributes = parsed != null ? parsed : AttributeJson.emptyObject();
            this.contentFrom = opener.end();
        }

        /** Takes the text up to the child's start, then the child itself. */
        void addChild(String source, int childStart, Block child, int childEnd) {
            if (childStart > contentFrom) {
                innerContent.add(source.substring(contentFrom, childStart));
            }
            innerBlocks.add(child);
            innerContent.add(null);
            contentFrom = childEnd;
        }

        /** Takes the text up to {@code end} as the last chunk; an empty one only when {@code keepEmpty}. */
        void finish(String source, int end, boolean keepEmpty) {
            if (end > contentFrom || keepEmpty) {
                innerContent.add(source.substring(contentFrom, end));
            }
        }

        /** A block from an opener; {@code rawCloser} is null when the input ended before the closer. */
        Block toBlock(String rawCloser, boolean recoveredCopy) {
            return new Block(
                    opener.name(),
                    attributes,
                    attributesValid,
                    opener.rawAttributes(),
                    rawOpener,
                    rawCloser,
                    recoveredCopy,
                    innerBlocks,
                    innerContent);
        }
    }

    private BlockParser(String s, ParseOptions options) {
        this.s = s;
        this.options = options;
        this.ends = new AttributeEnds(s);
    }

    static List<Block> parse(String s, ParseOptions options) {
        return new BlockParser(s, options).run();
    }

    /**
     * The parsed attributes of an opener or void delimiter: empty when absent, null when PHP would reject them
     * (undecodable text or JSON that {@code json_decode} refuses).
     */
    private static ObjectNode parseAttributes(Delimiter d) {
        if (d.rawAttributes() == null) {
            return AttributeJson.emptyObject();
        }
        return d.attributesDecodable() ? AttributeJson.parseOrNull(d.rawAttributes()) : null;
    }

    private List<Block> run() {
        int consumed = 0; // end of the last delimiter
        int searchFrom = 0;
        while (true) {
            int idx = s.indexOf(DelimiterScanner.COMMENT_OPEN, searchFrom);
            if (idx < 0) {
                break;
            }
            Delimiter d = DelimiterScanner.tryRead(s, idx, ends);
            if (d == null) {
                searchFrom = idx + 1;
                continue;
            }
            switch (d.kind()) {
                case OPENER -> {
                    if (frames.size() >= options.maxDepth()) {
                        throw new BlockParseException(
                                "block nesting depth exceeds maxDepth " + options.maxDepth(), idx);
                    }
                    frames.push(new Frame(s, d, idx > consumed ? consumed : -1));
                }
                case VOID -> {
                    Block block = voidBlock(d);
                    if (frames.isEmpty()) {
                        addFreeform(consumed, idx, false);
                        addTop(block, idx);
                    } else {
                        count(idx);
                        frames.peek().addChild(s, idx, block, d.end());
                    }
                }
                case CLOSER -> {
                    if (frames.isEmpty()) {
                        // nothing to close: like WordPress, stop and keep the rest as classic content
                        addFreeform(consumed, s.length(), false);
                        return List.copyOf(top);
                    }
                    Frame f = frames.pop();
                    String rawCloser = s.substring(d.start(), d.end());
                    if (frames.isEmpty()) {
                        f.finish(s, idx, false);
                        if (f.leadingStart >= 0) {
                            addFreeform(f.leadingStart, f.opener.start(), false);
                        }
                        addTop(f.toBlock(rawCloser, false), idx);
                    } else {
                        f.finish(s, idx, true);
                        count(idx);
                        frames.peek().addChild(s, f.opener.start(), f.toBlock(rawCloser, false), d.end());
                    }
                }
            }
            consumed = d.end();
            searchFrom = d.end();
        }
        if (frames.isEmpty()) {
            addFreeform(consumed, s.length(), false);
        }
        checkRecoveredChars();
        while (!frames.isEmpty()) {
            Frame f = frames.pop();
            boolean copy = !frames.isEmpty(); // the outermost open block also holds all of this text
            f.finish(s, s.length(), false);
            if (f.leadingStart >= 0) {
                addFreeform(f.leadingStart, f.opener.start(), copy);
            }
            addTop(f.toBlock(null, copy), s.length());
        }
        return List.copyOf(top);
    }

    /**
     * Checks the text that the recovered copies will repeat (each copy's last chunk and leading text) against the
     * budget, the larger of {@link ParseOptions#maxRecoveredChars()} and {@link
     * ParseOptions#recoveredCharsPerInputChar()} times the input length, before any of it is copied.
     */
    private void checkRecoveredChars() {
        long budget = options.recoveredCharsBudget(s.length());
        long total = 0;
        int copies = frames.size() - 1; // innermost first; the outermost open block is not a copy
        var it = frames.iterator();
        for (int i = 0; i < copies; i++) {
            Frame f = it.next();
            total += s.length() - f.contentFrom;
            if (f.leadingStart >= 0) {
                total += f.opener.start() - f.leadingStart;
            }
            if (total > budget) {
                throw new BlockParseException(
                        "blocks left open at the end repeat more than " + budget + " characters (maxRecoveredChars "
                                + options.maxRecoveredChars() + ", recoveredCharsPerInputChar "
                                + options.recoveredCharsPerInputChar() + ")",
                        f.opener.start());
            }
        }
    }

    /** A block from a void delimiter: no closer, no content. */
    private Block voidBlock(Delimiter d) {
        ObjectNode parsed = parseAttributes(d);
        return new Block(
                d.name(),
                parsed != null ? parsed : AttributeJson.emptyObject(),
                parsed != null,
                d.rawAttributes(),
                s.substring(d.start(), d.end()),
                null,
                false,
                List.of(),
                List.of());
    }

    private void addFreeform(int from, int to, boolean recoveredCopy) {
        if (from >= to) {
            return;
        }
        String text = s.substring(from, to);
        addTop(recoveredCopy ? Block.recoveredFreeform(text) : Block.freeform(text), from);
    }

    private void addTop(Block block, int offset) {
        count(offset);
        top.add(block);
    }

    private void count(int offset) {
        if (blockCount >= options.maxBlocks()) {
            throw new BlockParseException("document has more blocks than maxBlocks " + options.maxBlocks(), offset);
        }
        blockCount++;
    }
}
