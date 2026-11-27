package org.quarkword.lib.wpformatting;

import java.util.List;

/**
 * The walk of {@code convert_smilies} over HTML. The text is cut into chunks at every tag, a tag being {@code <},
 * everything up to the first later {@code >}, and that {@code >}; a {@code <} with no {@code >} after it stays in
 * its text chunk. Smileys are replaced only in non-empty chunks that do not start with {@code <}, and not while an
 * ignored element is open.
 *
 * <p>An opening chunk whose text after {@code <} starts with {@code code}, {@code pre}, {@code style},
 * {@code script} or {@code textarea} (a case-sensitive prefix test, so {@code <preview>} opens {@code pre}) opens
 * that element, unless one is already open. Only a chunk exactly {@code </name>} closes it, with no nesting count.
 */
final class SmileyWalker {

    private static final List<String> IGNORED_ELEMENTS = List.of("code", "pre", "style", "script", "textarea");

    private SmileyWalker() {}

    /**
     * The text with its smileys replaced; {@code replacements} is parallel to {@link SmileyTable#MATCH_ORDER}. The
     * text itself comes back when nothing is replaced.
     */
    static String convert(String text, List<String> replacements) {
        Walk walk = new Walk(text, replacements);
        int n = text.length();
        Forward closer = new Forward(text, ">");
        int i = 0;
        while (i < n) {
            int lt = text.indexOf('<', i);
            int gt = lt < 0 ? -1 : closer.from(lt + 1);
            if (gt < 0) {
                walk.chunk(i, n);
                break;
            }
            walk.chunk(i, lt);
            walk.chunk(lt, gt + 1);
            i = gt + 1;
        }
        return walk.result();
    }

    /** The state of one walk: the open ignored element and the output, made only once something changes. */
    private static final class Walk {

        private final String text;
        private final List<String> replacements;
        private String ignored;
        private StringBuilder out;
        private int copied;

        Walk(String text, List<String> replacements) {
            this.text = text;
            this.replacements = replacements;
        }

        void chunk(int start, int end) {
            if (start >= end) {
                return;
            }
            boolean markup = text.charAt(start) == '<';
            if (ignored == null && markup) {
                ignored = openedElement(start, end);
            }
            if (ignored == null && !markup) {
                replaceSmileys(start, end);
            }
            if (ignored != null && closes(start, end, ignored)) {
                ignored = null;
            }
        }

        private String openedElement(int start, int end) {
            for (String name : IGNORED_ELEMENTS) {
                if (start + 1 + name.length() <= end && text.startsWith(name, start + 1)) {
                    return name;
                }
            }
            return null;
        }

        private boolean closes(int start, int end, String name) {
            return end - start == name.length() + 3
                    && text.charAt(start) == '<'
                    && text.charAt(start + 1) == '/'
                    && text.startsWith(name, start + 2)
                    && text.charAt(end - 1) == '>';
        }

        /** Replaces each code that has a line start or a space before it and a line end or a space after it. */
        private void replaceSmileys(int start, int end) {
            SmileyTable.Boundary after =
                    position -> position == end || text.charAt(position) == '\n' || fitsAfter(position, end);
            int p = start;
            while (p < end) {
                if (SmileyTable.canStart(text.charAt(p)) && boundaryBefore(p, start)) {
                    int k = SmileyTable.match(text, p, end, after);
                    if (k >= 0) {
                        emit(p, replacements.get(k));
                        p += SmileyTable.MATCH_ORDER.get(k).code().length();
                        copied = p;
                        continue;
                    }
                }
                p++;
            }
        }

        private boolean boundaryBefore(int p, int start) {
            if (p == start || text.charAt(p - 1) == '\n') {
                return true;
            }
            int length = Spaces.lengthEndingAt(text, p);
            return length > 0 && p - length >= start;
        }

        private boolean fitsAfter(int position, int end) {
            int length = Spaces.lengthAt(text, position);
            return length > 0 && position + length <= end;
        }

        private void emit(int at, String replacement) {
            if (out == null) {
                out = new StringBuilder(text.length() + 16);
            }
            out.append(text, copied, at).append(replacement);
        }

        String result() {
            if (out == null) {
                return text;
            }
            return out.append(text, copied, text.length()).toString();
        }
    }
}
