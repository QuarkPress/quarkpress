package org.quarkword.lib.wpformatting;

/** Copy-on-change output for a pass over a text: nothing is allocated until the first edit. */
final class Edits {

    private Edits() {}

    /** {@code out}, or a new builder sized for {@code s} when this is the first edit. */
    static StringBuilder started(StringBuilder out, String s) {
        return out != null ? out : new StringBuilder(s.length() + (s.length() >> 4) + 16);
    }

    /** The edited text: {@code s} itself when nothing was edited, else {@code out} plus the rest of {@code s}. */
    static String finished(StringBuilder out, String s, int copied) {
        return out == null ? s : out.append(s, copied, s.length()).toString();
    }
}
