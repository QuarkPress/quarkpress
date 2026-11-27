package org.quarkword.lib.wpformatting;

/**
 * wpautop's line breaks: newlines inside script, style, svg and math elements are kept, the {@code <br>} spellings
 * are normalised, and every other newline that ends a whitespace run becomes {@code <br />} + newline.
 */
final class LineBreaks {

    static final String PRESERVED_NEWLINE = "<WPPreserveNewline />";
    private static final String BR = "<br />";
    private static final String[] RAW_TEXT = {"script", "style", "svg", "math"};

    private LineBreaks() {}

    static String apply(String s) {
        s = guardRawText(s);
        s = s.replace("<br>", BR).replace("<br/>", BR);
        s = insertBreaks(s);
        return s.replace(PRESERVED_NEWLINE, "\n");
    }

    /** Every line feed of {@code span} replaced by the preserved-newline placeholder. */
    static String preserveNewlines(String span) {
        return span.replace("\n", PRESERVED_NEWLINE);
    }

    /**
     * From each {@code <script}, {@code <style}, {@code <svg} or {@code <math} (as a prefix) through the first
     * closing tag of the same name, newlines become placeholders. An opener with no closer after it is left alone,
     * and so is every later opener of that name.
     */
    private static String guardRawText(String s) {
        Forward[] closers = new Forward[RAW_TEXT.length];
        boolean[] closed = new boolean[RAW_TEXT.length];
        for (int k = 0; k < RAW_TEXT.length; k++) {
            closers[k] = new Forward(s, "</" + RAW_TEXT[k] + ">");
        }
        StringBuilder out = null;
        int copied = 0;
        int at = s.indexOf('<');
        while (at >= 0) {
            int end = -1;
            for (int k = 0; k < RAW_TEXT.length && end < 0; k++) {
                String name = RAW_TEXT[k];
                if (closed[k] || !s.startsWith(name, at + 1)) {
                    continue;
                }
                int close = closers[k].from(at + 1 + name.length());
                if (close < 0) {
                    closed[k] = true;
                } else {
                    end = close + name.length() + 3;
                }
            }
            if (end < 0) {
                at = s.indexOf('<', at + 1);
                continue;
            }
            out = Edits.started(out, s);
            out.append(s, copied, at).append(preserveNewlines(s.substring(at, end)));
            copied = end;
            at = s.indexOf('<', copied);
        }
        return Edits.finished(out, s, copied);
    }

    /**
     * Each whitespace run that holds a line feed is replaced, from its start through its last line feed, by
     * {@code <br />} + newline; whitespace after that line feed stays. A run right after {@code <br />} keeps its
     * first char, so an existing break is not doubled.
     */
    private static String insertBreaks(String s) {
        int length = s.length();
        StringBuilder out = null;
        int copied = 0;
        int i = 0;
        while (i < length) {
            if (!Chars.isPcreSpace(s.charAt(i))) {
                i++;
                continue;
            }
            int runStart = i;
            int lastLineFeed = -1;
            while (i < length && Chars.isPcreSpace(s.charAt(i))) {
                if (s.charAt(i) == '\n') {
                    lastLineFeed = i;
                }
                i++;
            }
            int start = runStart >= BR.length() && s.startsWith(BR, runStart - BR.length()) ? runStart + 1 : runStart;
            if (lastLineFeed < start) {
                continue;
            }
            out = Edits.started(out, s);
            out.append(s, copied, start).append(BR).append('\n');
            copied = lastLineFeed + 1;
        }
        return Edits.finished(out, s, copied);
    }
}
