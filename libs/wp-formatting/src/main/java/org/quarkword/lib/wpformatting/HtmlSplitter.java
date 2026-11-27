package org.quarkword.lib.wpformatting;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits text into alternating text and markup chunks, starting and ending with text. A markup chunk starts at
 * each {@code <}: a comment ({@code <!--}) runs through the first {@code -->} after the {@code <}, so that
 * {@code <!-->} is complete; CDATA ({@code <![CDATA[}) runs through the first {@code ]]>}; anything else runs
 * through the next {@code >}. A chunk without its terminator runs to the end of the text. Quotes are not special.
 */
final class HtmlSplitter {

    /** Receives each chunk as a range of the text. */
    @FunctionalInterface
    interface ChunkVisitor {
        void chunk(int start, int end, boolean markup);
    }

    private static final String COMMENT_OPEN = "!--";
    private static final String COMMENT_CLOSE = "-->";
    private static final String CDATA_OPEN = "![CDATA[";
    private static final String CDATA_CLOSE = "]]>";

    private HtmlSplitter() {}

    static List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        forEachChunk(text, (start, end, markup) -> chunks.add(text.substring(start, end)));
        return chunks;
    }

    /** Calls {@code visitor} for each chunk in order: text, markup, text, …, text. */
    static void forEachChunk(String text, ChunkVisitor visitor) {
        int length = text.length();
        Forward lessThan = new Forward(text, "<");
        Forward greaterThan = new Forward(text, ">");
        Forward commentClose = new Forward(text, COMMENT_CLOSE);
        Forward cdataClose = new Forward(text, CDATA_CLOSE);
        int textStart = 0;
        while (true) {
            int open = lessThan.from(textStart);
            if (open < 0) {
                visitor.chunk(textStart, length, false);
                return;
            }
            visitor.chunk(textStart, open, false);
            int end;
            if (text.startsWith(COMMENT_OPEN, open + 1)) {
                end = endAfter(commentClose.from(open + 1), COMMENT_CLOSE.length(), length);
            } else if (text.startsWith(CDATA_OPEN, open + 1)) {
                end = endAfter(cdataClose.from(open + 1 + CDATA_OPEN.length()), CDATA_CLOSE.length(), length);
            } else {
                end = endAfter(greaterThan.from(open + 1), 1, length);
            }
            visitor.chunk(open, end, true);
            textStart = end;
        }
    }

    private static int endAfter(int found, int terminatorLength, int length) {
        return found < 0 ? length : found + terminatorLength;
    }
}
