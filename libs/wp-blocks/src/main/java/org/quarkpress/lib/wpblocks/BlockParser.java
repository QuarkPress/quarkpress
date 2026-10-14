package org.quarkpress.lib.wpblocks;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.quarkpress.lib.wpblocks.DelimiterScanner.Delimiter;

/**
 * Single-pass scanner with an explicit frame stack. Every character of the input ends up in exactly
 * one place: a delimiter (kept verbatim as the block's raw opener or closer and written back as is,
 * not reconstructed), a text chunk of an open block, or a freeform block. That is what makes the round
 * trip exact.
 */
final class BlockParser {

    private final String s;
    private final ParseOptions options;
    private final List<Block> top = new ArrayList<>();
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private final AttributeEnds ends;
    private int blockCount;

    private static final class Frame {
        final String name;
        final String rawAttributes;
        final String rawOpener;
        final ObjectNode attributes;
        final List<Block> innerBlocks = new ArrayList<>();
        final List<String> innerContent = new ArrayList<>();

        Frame(String source, Delimiter d) {
            this.name = d.name();
            this.rawAttributes = d.rawAttributes();
            this.rawOpener = source.substring(d.start(), d.end());
            this.attributes =
                    d.rawAttributes() == null ? AttributeJson.emptyObject() : AttributeJson.parse(d.rawAttributes());
        }

        void appendText(String text) {
            if (text.isEmpty()) {
                return;
            }
            int last = innerContent.size() - 1;
            if (last >= 0 && innerContent.get(last) != null) {
                innerContent.set(last, innerContent.get(last) + text);
            } else {
                innerContent.add(text);
            }
        }

        /** A block from a void delimiter: no closer, not explicitly closed. */
        Block toVoidBlock() {
            return new Block(name, attributes, rawAttributes, rawOpener, null, false, innerBlocks, innerContent);
        }

        /** A block from an opener; {@code rawCloser} is null when the input ended before the closer. */
        Block toBlock(String rawCloser) {
            return new Block(name, attributes, rawAttributes, rawOpener, rawCloser, true, innerBlocks, innerContent);
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

    private List<Block> run() {
        int textStart = 0; // start of the text run not yet assigned to a block
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
                    flushText(textStart, idx);
                    if (frames.size() >= options.maxDepth()) {
                        throw new BlockParseException(
                                "block nesting depth exceeds maxDepth " + options.maxDepth(), idx);
                    }
                    frames.push(new Frame(s, d));
                }
                case VOID -> {
                    flushText(textStart, idx);
                    addBlock(new Frame(s, d).toVoidBlock(), idx);
                }
                case CLOSER -> {
                    if (frames.isEmpty()) {
                        // a closer with nothing open is ordinary text; keep the text run going
                        searchFrom = d.end();
                        continue;
                    }
                    flushText(textStart, idx);
                    Frame f = frames.pop();
                    addBlock(f.toBlock(s.substring(d.start(), d.end())), idx);
                }
            }
            textStart = d.end();
            searchFrom = d.end();
        }
        if (frames.isEmpty()) {
            flushText(textStart, s.length());
        } else {
            frames.peek().appendText(s.substring(textStart));
            while (!frames.isEmpty()) {
                Frame f = frames.pop();
                addBlock(f.toBlock(null), s.length());
            }
        }
        return List.copyOf(top);
    }

    /** Assigns {@code s[from, to)} to the open block, or to a new freeform block at top level. */
    private void flushText(int from, int to) {
        if (from >= to) {
            return;
        }
        String text = s.substring(from, to);
        if (frames.isEmpty()) {
            addBlock(Block.freeform(text), from);
        } else {
            frames.peek().appendText(text);
        }
    }

    private void addBlock(Block block, int offset) {
        if (blockCount >= options.maxBlocks()) {
            throw new BlockParseException("document has more blocks than maxBlocks " + options.maxBlocks(), offset);
        }
        blockCount++;
        if (frames.isEmpty()) {
            top.add(block);
        } else {
            Frame parent = frames.peek();
            parent.innerBlocks.add(block);
            parent.innerContent.add(null);
        }
    }
}
