package org.quarkword.lib.wpformatting;

/**
 * wpautop: double line breaks become paragraphs and single ones {@code <br />}, as WordPress does it. The steps run
 * in WordPress's order, each as its own linear pass over the current text; several of WordPress's surprising
 * outputs come only from that order.
 */
final class Autop {

    private static final String TAG_NEWLINE = "<!-- wpnl -->";
    private static final String SPACED_TAG_NEWLINE = " " + TAG_NEWLINE + " ";

    private Autop() {}

    /** Throws {@link FormattingLimitException} when the text would grow longer than {@code limit} chars. */
    static String autop(String text, boolean br, long limit) {
        if (isBlank(text)) {
            return "";
        }
        String s = text + "\n";
        PreBlocks pre = PreBlocks.protect(s);
        if (pre != null) {
            s = pre.text();
        }
        s = doubleBreaksToParagraphs(s);
        s = spaceBeforeBlockOpeners(s);
        s = spaceAfterBlockClosers(s);
        s = spaceAfterBareRules(s);
        s = normaliseNewlines(s);
        s = protectNewlinesInMarkup(s);
        s = AutopCleanups.beforeParagraphs(s);
        s = collapseBlankLines(s);
        s = wrapParagraphs(s);
        s = dropEmptyParagraphs(s);
        s = AutopCleanups.fixParagraphs(s);
        if (br) {
            s = LineBreaks.apply(s);
        }
        s = AutopCleanups.deleteBreaksAfterBlockTags(s);
        s = AutopCleanups.deleteBreaksBeforeBlockTags(s);
        s = dropNewlineBeforeLastParagraphEnd(s);
        if (pre != null) {
            s = pre.restore(s, limit);
        }
        if (s.contains(TAG_NEWLINE)) {
            s = s.replace(SPACED_TAG_NEWLINE, "\n").replace(TAG_NEWLINE, "\n");
        }
        if (s.length() > limit) {
            throw new FormattingLimitException("wpautop", s.length(), limit);
        }
        return s;
    }

    /** {@code span} with every line feed replaced by {@code <WPPreserveNewline />}. */
    static String preserveNewlines(String span) {
        return LineBreaks.preserveNewlines(span);
    }

    /** Only chars PHP's {@code trim} removes (form feed is not one of them). */
    private static boolean isBlank(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (!Chars.isPhpTrim(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** The end of a {@code <br>} tag at {@code i} ({@code <br}, whitespace, an optional {@code /}, {@code >}), or -1. */
    private static int breakTagEnd(String s, int i) {
        if (!s.startsWith("<br", i)) {
            return -1;
        }
        int j = AutopCleanups.skipSpace(s, i + 3);
        if (j < s.length() && s.charAt(j) == '/') {
            j++;
        }
        return j < s.length() && s.charAt(j) == '>' ? j + 1 : -1;
    }

    /** Two {@code <br>} tags with only whitespace between them become a blank line. */
    private static String doubleBreaksToParagraphs(String s) {
        StringBuilder out = null;
        int copied = 0;
        int at = s.indexOf("<br");
        while (at >= 0) {
            int first = breakTagEnd(s, at);
            int second = first < 0 ? -1 : breakTagEnd(s, AutopCleanups.skipSpace(s, first));
            if (second < 0) {
                at = s.indexOf("<br", at + 1);
                continue;
            }
            out = Edits.started(out, s);
            out.append(s, copied, at).append("\n\n");
            copied = second;
            at = s.indexOf("<br", copied);
        }
        return Edits.finished(out, s, copied);
    }

    /** A blank line before each block opener ({@code <} + block name + whitespace, {@code /} or {@code >}). */
    private static String spaceBeforeBlockOpeners(String s) {
        StringBuilder out = null;
        int copied = 0;
        for (int at = s.indexOf('<'); at >= 0; at = s.indexOf('<', at + 1)) {
            if (BlockNames.nameAtBoundary(s, at + 1)) {
                out = Edits.started(out, s);
                out.append(s, copied, at).append("\n\n");
                copied = at;
            }
        }
        return Edits.finished(out, s, copied);
    }

    /** A blank line after each block closer ({@code </} + block name + {@code >}). */
    private static String spaceAfterBlockClosers(String s) {
        StringBuilder out = null;
        int copied = 0;
        for (int at = s.indexOf("</"); at >= 0; at = s.indexOf("</", at + 1)) {
            if (BlockNames.nameBeforeGreaterThan(s, at + 2)) {
                int end = s.indexOf('>', at + 2) + 1;
                out = Edits.started(out, s);
                out.append(s, copied, end).append("\n\n");
                copied = end;
            }
        }
        return Edits.finished(out, s, copied);
    }

    /** A blank line after each attribute-less {@code <hr>}, {@code <hr/>} or {@code <hr />}. */
    private static String spaceAfterBareRules(String s) {
        StringBuilder out = null;
        int copied = 0;
        for (int at = s.indexOf("<hr"); at >= 0; at = s.indexOf("<hr", at + 1)) {
            int j = AutopCleanups.skipSpace(s, at + 3);
            if (j < s.length() && s.charAt(j) == '/') {
                j++;
            }
            if (j < s.length() && s.charAt(j) == '>') {
                out = Edits.started(out, s);
                out.append(s, copied, j + 1).append("\n\n");
                copied = j + 1;
            }
        }
        return Edits.finished(out, s, copied);
    }

    /**
     * Each line feed inside markup (a tag, comment or CDATA section, as {@code wp_html_split} finds them) becomes
     * a spaced {@code <!-- wpnl -->} comment, so later steps neither split nor break there.
     */
    private static String protectNewlinesInMarkup(String s) {
        if (s.indexOf('\n') < 0 || s.indexOf('<') < 0) {
            return s;
        }
        int[] inMarkup = {0};
        Forward counter = new Forward(s, "\n");
        HtmlSplitter.forEachChunk(s, (start, end, markup) -> {
            for (int i = counter.from(start); markup && i >= 0 && i < end; i = counter.from(i + 1)) {
                inMarkup[0]++;
            }
        });
        if (inMarkup[0] == 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length() + inMarkup[0] * (SPACED_TAG_NEWLINE.length() - 1));
        Forward lineFeed = new Forward(s, "\n");
        HtmlSplitter.forEachChunk(s, (start, end, markup) -> {
            if (!markup) {
                out.append(s, start, end);
                return;
            }
            int copied = start;
            for (int i = lineFeed.from(start); i >= 0 && i < end; i = lineFeed.from(i + 1)) {
                out.append(s, copied, i).append(SPACED_TAG_NEWLINE);
                copied = i + 1;
            }
            out.append(s, copied, end);
        });
        return out.toString();
    }

    /** CR LF and lone CR become LF. */
    private static String normaliseNewlines(String s) {
        if (s.indexOf('\r') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r') {
                out.append('\n');
                if (i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                    i++;
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Runs of two or more line feeds become exactly two. */
    private static String collapseBlankLines(String s) {
        if (!s.contains("\n\n\n")) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            out.append(c);
            i++;
            if (c == '\n' && i < s.length() && s.charAt(i) == '\n') {
                out.append('\n');
                while (i < s.length() && s.charAt(i) == '\n') {
                    i++;
                }
            }
        }
        return out.toString();
    }

    /**
     * Splits at each line feed + whitespace + line feed (the whitespace run taken through its last line feed),
     * drops empty pieces, and wraps each piece, stripped of line feeds at both ends, in {@code <p>…</p>} + LF.
     */
    private static String wrapParagraphs(String s) {
        int length = s.length();
        StringBuilder out = new StringBuilder(length + length / 4 + 16);
        int piece = 0;
        int i = 0;
        while (i < length) {
            if (s.charAt(i) != '\n') {
                i++;
                continue;
            }
            int j = i + 1;
            int lastLineFeed = -1;
            while (j < length && Chars.isPcreSpace(s.charAt(j))) {
                if (s.charAt(j) == '\n') {
                    lastLineFeed = j;
                }
                j++;
            }
            if (lastLineFeed < 0) {
                i = j;
                continue;
            }
            wrap(out, s, piece, i);
            piece = lastLineFeed + 1;
            i = piece;
        }
        wrap(out, s, piece, length);
        return out.toString();
    }

    private static void wrap(StringBuilder out, String s, int start, int end) {
        if (start >= end) {
            return;
        }
        while (start < end && s.charAt(start) == '\n') {
            start++;
        }
        while (end > start && s.charAt(end - 1) == '\n') {
            end--;
        }
        out.append("<p>").append(s, start, end).append("</p>\n");
    }

    /** {@code <p>} + whitespace + {@code </p>} is removed. */
    private static String dropEmptyParagraphs(String s) {
        StringBuilder out = null;
        int copied = 0;
        int at = s.indexOf("<p>");
        while (at >= 0) {
            int close = AutopCleanups.skipSpace(s, at + 3);
            if (!s.startsWith("</p>", close)) {
                at = s.indexOf("<p>", at + 1);
                continue;
            }
            out = Edits.started(out, s);
            out.append(s, copied, at);
            copied = close + 4;
            at = s.indexOf("<p>", copied);
        }
        return Edits.finished(out, s, copied);
    }

    /** A text ending in LF + {@code </p>}, with or without one more LF, loses the LF before {@code </p>}. */
    private static String dropNewlineBeforeLastParagraphEnd(String s) {
        String end = "\n</p>";
        if (s.endsWith(end)) {
            return s.substring(0, s.length() - end.length()) + "</p>";
        }
        if (s.endsWith(end + "\n")) {
            return s.substring(0, s.length() - end.length() - 1) + "</p>\n";
        }
        return s;
    }
}
