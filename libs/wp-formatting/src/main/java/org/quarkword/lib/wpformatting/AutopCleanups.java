package org.quarkword.lib.wpformatting;

import java.util.List;

/**
 * wpautop's clean-up passes: whitespace removal around form, object and media tags before paragraphs are made, the
 * paragraph fix-ups after, and the removal of {@code <br />} next to block tags. Block tags are matched by name
 * prefix, as WordPress does, so {@code p} also stands for {@code <progress>}. Each pass is one linear scan.
 */
final class AutopCleanups {

    private static final String P_OPEN = "<p>";
    private static final String P_CLOSE = "</p>";
    private static final String BR = "<br />";
    private static final List<String> CONTAINER_CLOSERS = List.of("</div>", "</address>", "</form>");

    private AutopCleanups() {}

    // ---- before paragraphs

    /** The whitespace clean-ups around option, object, param, embed, media, source, track and figcaption tags. */
    static String beforeParagraphs(String s) {
        if (s.contains("<option")) {
            s = deleteSpaceBefore(s, "<option");
            s = deleteSpaceAfter(s, "</option>");
        }
        if (s.contains("</object>")) {
            s = deleteSpaceAfterTag(s, "<object");
            s = deleteSpaceBefore(s, "</object>");
            s = deleteSpaceAroundTags(s, true, "param", "embed");
        }
        if (s.contains("<source") || s.contains("<track")) {
            s = deleteSpaceAfterMediaOpeners(s);
            s = deleteSpaceBeforeMediaClosers(s);
            s = deleteSpaceAroundTags(s, false, "source", "track");
        }
        if (s.contains("<figcaption")) {
            s = deleteSpaceBeforeTag(s, "<figcaption");
            s = deleteSpaceAfter(s, "</figcaption>");
        }
        return s;
    }

    /** Removes the whitespace run right before each {@code needle}. */
    private static String deleteSpaceBefore(String s, String needle) {
        StringBuilder out = null;
        int copied = 0;
        for (int at = s.indexOf(needle); at >= 0; at = s.indexOf(needle, at + needle.length())) {
            out = Edits.started(out, s);
            out.append(s, copied, spaceStart(s, at, copied));
            copied = at;
        }
        return Edits.finished(out, s, copied);
    }

    /** Removes the whitespace run right after each {@code needle}. */
    private static String deleteSpaceAfter(String s, String needle) {
        StringBuilder out = null;
        int copied = 0;
        int at = s.indexOf(needle);
        while (at >= 0) {
            int end = at + needle.length();
            out = Edits.started(out, s);
            out.append(s, copied, end);
            copied = skipSpace(s, end);
            at = s.indexOf(needle, copied);
        }
        return Edits.finished(out, s, copied);
    }

    /** Removes the whitespace after each tag that starts with {@code prefix} and runs to its first {@code >}. */
    private static String deleteSpaceAfterTag(String s, String prefix) {
        Forward greaterThan = new Forward(s, ">");
        StringBuilder out = null;
        int copied = 0;
        int at = s.indexOf(prefix);
        while (at >= 0) {
            int gt = greaterThan.from(at + prefix.length());
            if (gt < 0) {
                break;
            }
            out = Edits.started(out, s);
            out.append(s, copied, gt + 1);
            copied = skipSpace(s, gt + 1);
            at = s.indexOf(prefix, copied);
        }
        return Edits.finished(out, s, copied);
    }

    /** Removes the whitespace before each tag that starts with {@code prefix} and has a {@code >}. */
    private static String deleteSpaceBeforeTag(String s, String prefix) {
        Forward greaterThan = new Forward(s, ">");
        StringBuilder out = null;
        int copied = 0;
        int at = s.indexOf(prefix);
        while (at >= 0) {
            int gt = greaterThan.from(at + prefix.length());
            if (gt < 0) {
                break;
            }
            out = Edits.started(out, s);
            out.append(s, copied, spaceStart(s, at, copied)).append(s, at, gt + 1);
            copied = gt + 1;
            at = s.indexOf(prefix, copied);
        }
        return Edits.finished(out, s, copied);
    }

    /**
     * Removes the whitespace on both sides of each tag ({@code <}, an optional {@code /} when allowed, a name
     * starting with one of {@code names}, then up to the first {@code >}).
     */
    private static String deleteSpaceAroundTags(String s, boolean closersToo, String... names) {
        Forward greaterThan = new Forward(s, ">");
        StringBuilder out = null;
        int copied = 0;
        int at = s.indexOf('<');
        while (at >= 0) {
            int name = at + 1;
            if (closersToo && name < s.length() && s.charAt(name) == '/') {
                name++;
            }
            int gt = startsWithAny(s, name, names) ? greaterThan.from(name) : -1;
            if (gt < 0) {
                at = s.indexOf('<', at + 1);
                continue;
            }
            out = Edits.started(out, s);
            out.append(s, copied, spaceStart(s, at, copied)).append(s, at, gt + 1);
            copied = skipSpace(s, gt + 1);
            at = s.indexOf('<', copied);
        }
        return Edits.finished(out, s, copied);
    }

    /**
     * Removes the whitespace after each media opener: {@code <} or {@code [}, then {@code audio} or {@code video},
     * then up to the first {@code >} or {@code ]}. This covers tags and the audio and video shortcodes.
     */
    private static String deleteSpaceAfterMediaOpeners(String s) {
        Forward greaterThan = new Forward(s, ">");
        Forward bracket = new Forward(s, "]");
        StringBuilder out = null;
        int copied = 0;
        int i = 0;
        while (i < s.length()) {
            if (!isMediaStart(s, i) || !startsWithAny(s, i + 1, "audio", "video")) {
                i++;
                continue;
            }
            int end = firstOf(greaterThan.from(i + 6), bracket.from(i + 6));
            if (end < 0) {
                break;
            }
            out = Edits.started(out, s);
            out.append(s, copied, end + 1);
            copied = skipSpace(s, end + 1);
            i = copied;
        }
        return Edits.finished(out, s, copied);
    }

    /** Removes the whitespace before each media closer: {@code <} or {@code [}, {@code /audio} or {@code /video}, {@code >} or {@code ]}. */
    private static String deleteSpaceBeforeMediaClosers(String s) {
        StringBuilder out = null;
        int copied = 0;
        int i = 0;
        while (i < s.length()) {
            int last = i + 7;
            if (isMediaStart(s, i)
                    && last < s.length()
                    && s.charAt(i + 1) == '/'
                    && startsWithAny(s, i + 2, "audio", "video")
                    && (s.charAt(last) == '>' || s.charAt(last) == ']')) {
                out = Edits.started(out, s);
                out.append(s, copied, spaceStart(s, i, copied)).append(s, i, last + 1);
                copied = last + 1;
                i = copied;
            } else {
                i++;
            }
        }
        return Edits.finished(out, s, copied);
    }

    private static boolean isMediaStart(String s, int i) {
        char c = s.charAt(i);
        return c == '<' || c == '[';
    }

    private static int firstOf(int a, int b) {
        if (a < 0) {
            return b;
        }
        return b < 0 ? a : Math.min(a, b);
    }

    // ---- paragraph fix-ups

    /** The fix-ups that run after wrapping, in WordPress's order. */
    static String fixParagraphs(String s) {
        s = closeBeforeContainerEnds(s);
        s = unwrapLoneBlockTags(s);
        s = unwrapListItems(s);
        s = moveBlockquoteParagraphs(s);
        s = s.replace("</blockquote></p>", "</p></blockquote>");
        s = stripParagraphBeforeBlockTags(s);
        s = stripAfterBlockTags(s, P_CLOSE);
        return s;
    }

    /** {@code <p>} + text without {@code <} + {@code </div>}, {@code </address>} or {@code </form>} gets its {@code </p>}. */
    private static String closeBeforeContainerEnds(String s) {
        Forward lessThan = new Forward(s, "<");
        StringBuilder out = null;
        int copied = 0;
        int at = s.indexOf(P_OPEN);
        while (at >= 0) {
            int text = at + P_OPEN.length();
            int lt = lessThan.from(text);
            if (lt < 0) {
                break;
            }
            String closer = lt > text ? containerCloserAt(s, lt) : null;
            if (closer == null) {
                at = s.indexOf(P_OPEN, at + 1);
                continue;
            }
            out = Edits.started(out, s);
            out.append(s, copied, lt).append(P_CLOSE).append(closer);
            copied = lt + closer.length();
            at = s.indexOf(P_OPEN, copied);
        }
        return Edits.finished(out, s, copied);
    }

    private static String containerCloserAt(String s, int i) {
        for (String closer : CONTAINER_CLOSERS) {
            if (s.startsWith(closer, i)) {
                return closer;
            }
        }
        return null;
    }

    /** {@code <p>} + whitespace + one block tag + whitespace + {@code </p>} becomes the tag. */
    private static String unwrapLoneBlockTags(String s) {
        Forward greaterThan = new Forward(s, ">");
        StringBuilder out = null;
        int copied = 0;
        int at = s.indexOf(P_OPEN);
        while (at >= 0) {
            int tag = skipSpace(s, at + P_OPEN.length());
            int tagEnd = BlockNames.tagEnd(s, tag, greaterThan);
            int close = tagEnd < 0 ? -1 : skipSpace(s, tagEnd);
            if (close < 0 || !s.startsWith(P_CLOSE, close)) {
                at = s.indexOf(P_OPEN, at + 1);
                continue;
            }
            out = Edits.started(out, s);
            out.append(s, copied, at).append(s, tag, tagEnd);
            copied = close + P_CLOSE.length();
            at = s.indexOf(P_OPEN, copied);
        }
        return Edits.finished(out, s, copied);
    }

    /**
     * {@code <p>} + {@code <li} (a prefix, so {@code <link} too) + at least one more char up to the first
     * {@code </p>}, all on one line, loses the {@code <p>} and the {@code </p>}.
     */
    private static String unwrapListItems(String s) {
        Forward paragraphEnd = new Forward(s, P_CLOSE);
        Forward lineFeed = new Forward(s, "\n");
        StringBuilder out = null;
        int copied = 0;
        String opener = "<p><li";
        int at = s.indexOf(opener);
        while (at >= 0) {
            int rest = at + opener.length();
            int end = paragraphEnd.from(rest + 1);
            if (end < 0) {
                break;
            }
            int lf = lineFeed.from(rest);
            if (lf >= 0 && lf < end) {
                at = s.indexOf(opener, at + 1);
                continue;
            }
            out = Edits.started(out, s);
            out.append(s, copied, at).append(s, at + P_OPEN.length(), end);
            copied = end + P_CLOSE.length();
            at = s.indexOf(opener, copied);
        }
        return Edits.finished(out, s, copied);
    }

    /**
     * {@code <p><blockquote …>}, matched without regard to ASCII case, becomes {@code <blockquote …><p>}. The
     * attributes may hold {@code >} inside complete quoted strings.
     */
    private static String moveBlockquoteParagraphs(String s) {
        String opener = "<p><blockquote";
        int at = indexOfAsciiIgnoreCase(s, opener, 0);
        if (at < 0) {
            return s;
        }
        int[] tagEnd = attributeRunEnds(s);
        StringBuilder out = null;
        int copied = 0;
        while (at >= 0) {
            int attributes = at + opener.length();
            int gt = tagEnd[attributes];
            if (gt < 0) {
                at = indexOfAsciiIgnoreCase(s, opener, at + 1);
                continue;
            }
            out = Edits.started(out, s);
            out.append(s, copied, at)
                    .append("<blockquote")
                    .append(s, attributes, gt)
                    .append("><p>");
            copied = gt + 1;
            at = indexOfAsciiIgnoreCase(s, opener, copied);
        }
        return Edits.finished(out, s, copied);
    }

    /**
     * For each start position, the {@code >} that ends an attribute run starting there (chars other than
     * {@code >} and quotes, or complete quoted strings), or -1 when the run cannot end. One pass from the right.
     */
    private static int[] attributeRunEnds(String s) {
        int length = s.length();
        int[] end = new int[length + 1];
        end[length] = -1;
        int nextDouble = -1;
        int nextSingle = -1;
        for (int i = length - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == '>') {
                end[i] = i;
            } else if (c == '"') {
                end[i] = nextDouble < 0 ? -1 : end[nextDouble + 1];
                nextDouble = i;
            } else if (c == '\'') {
                end[i] = nextSingle < 0 ? -1 : end[nextSingle + 1];
                nextSingle = i;
            } else {
                end[i] = end[i + 1];
            }
        }
        return end;
    }

    /** {@code <p>} + whitespace + a block tag becomes the tag. */
    private static String stripParagraphBeforeBlockTags(String s) {
        Forward greaterThan = new Forward(s, ">");
        StringBuilder out = null;
        int copied = 0;
        int at = s.indexOf(P_OPEN);
        while (at >= 0) {
            int tag = skipSpace(s, at + P_OPEN.length());
            int tagEnd = BlockNames.tagEnd(s, tag, greaterThan);
            if (tagEnd < 0) {
                at = s.indexOf(P_OPEN, at + 1);
                continue;
            }
            out = Edits.started(out, s);
            out.append(s, copied, at).append(s, tag, tagEnd);
            copied = tagEnd;
            at = s.indexOf(P_OPEN, copied);
        }
        return Edits.finished(out, s, copied);
    }

    /** A block tag + whitespace + {@code follower} becomes the tag. */
    private static String stripAfterBlockTags(String s, String follower) {
        Forward greaterThan = new Forward(s, ">");
        StringBuilder out = null;
        int copied = 0;
        int spacedFrom = -1;
        int spacedTo = -1;
        int at = s.indexOf('<');
        while (at >= 0) {
            int tagEnd = BlockNames.tagEnd(s, at, greaterThan);
            if (tagEnd < 0) {
                at = s.indexOf('<', at + 1);
                continue;
            }
            if (tagEnd != spacedFrom) {
                // tags that share their first ">" share the whitespace after it
                spacedFrom = tagEnd;
                spacedTo = skipSpace(s, tagEnd);
            }
            if (!s.startsWith(follower, spacedTo)) {
                at = s.indexOf('<', at + 1);
                continue;
            }
            out = Edits.started(out, s);
            out.append(s, copied, tagEnd);
            copied = spacedTo + follower.length();
            at = s.indexOf('<', copied);
        }
        return Edits.finished(out, s, copied);
    }

    // ---- line breaks next to block tags

    /** A block tag + whitespace + {@code <br />} becomes the tag. */
    static String deleteBreaksAfterBlockTags(String s) {
        return s.contains(BR) ? stripAfterBlockTags(s, BR) : s;
    }

    /**
     * {@code <br />} before whitespace and a tag whose name starts with p, li, div, dl, dd, dt, th, pre, td, ul or
     * ol is removed; the whitespace stays.
     */
    static String deleteBreaksBeforeBlockTags(String s) {
        int at = s.indexOf(BR);
        if (at < 0) {
            return s;
        }
        Forward greaterThan = new Forward(s, ">");
        StringBuilder out = null;
        int copied = 0;
        while (at >= 0) {
            int lt = skipSpace(s, at + BR.length());
            int gt = -1;
            if (lt < s.length() && s.charAt(lt) == '<') {
                int name = lt + 1;
                if (name < s.length() && s.charAt(name) == '/') {
                    name++;
                }
                if (BlockNames.breakEnderAt(s, name)) {
                    gt = greaterThan.from(name);
                }
            }
            if (gt < 0) {
                at = s.indexOf(BR, at + 1);
                continue;
            }
            out = Edits.started(out, s);
            out.append(s, copied, at).append(s, at + BR.length(), gt + 1);
            copied = gt + 1;
            at = s.indexOf(BR, copied);
        }
        return Edits.finished(out, s, copied);
    }

    // ---- helpers

    /** The start of the whitespace run that ends at {@code end}, not before {@code floor}. */
    private static int spaceStart(String s, int end, int floor) {
        int i = end;
        while (i > floor && Chars.isPcreSpace(s.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    static int skipSpace(String s, int i) {
        while (i < s.length() && Chars.isPcreSpace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean startsWithAny(String s, int i, String... prefixes) {
        for (String prefix : prefixes) {
            if (s.startsWith(prefix, i)) {
                return true;
            }
        }
        return false;
    }

    /** The first index at or after {@code from} where {@code needle} (ASCII lower case) starts, ignoring ASCII case. */
    private static int indexOfAsciiIgnoreCase(String s, String needle, int from) {
        int last = s.length() - needle.length();
        for (int i = from; i <= last; i = s.indexOf('<', i + 1)) {
            if (i < 0) {
                return -1;
            }
            if (s.charAt(i) == '<' && regionMatchesAsciiIgnoreCase(s, i, needle)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean regionMatchesAsciiIgnoreCase(String s, int at, String lowerNeedle) {
        for (int k = 0; k < lowerNeedle.length(); k++) {
            char c = s.charAt(at + k);
            if (c >= 'A' && c <= 'Z') {
                c = (char) (c + ('a' - 'A'));
            }
            if (c != lowerNeedle.charAt(k)) {
                return false;
            }
        }
        return true;
    }
}
