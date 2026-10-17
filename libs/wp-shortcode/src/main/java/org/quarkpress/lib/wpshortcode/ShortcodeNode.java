package org.quarkpress.lib.wpshortcode;

import java.util.Objects;

/**
 * One piece of {@link Shortcodes#parse}: text, an escaped shortcode ({@code [[tag]]}, which renders as literal
 * text) or a shortcode. Concatenating every node's {@link #source()} gives the protected input: brackets inside
 * HTML markup appear as {@code &#91;}/{@code &#93;} and literal {@code &#91;} as {@code &#091;}.
 */
public sealed interface ShortcodeNode permits ShortcodeNode.Text, ShortcodeNode.Escaped, ShortcodeNode.Match {

    /** This node's text in the protected input. */
    String source();

    record Text(String text) implements ShortcodeNode {
        public Text {
            Objects.requireNonNull(text, "text");
        }

        @Override
        public String source() {
            return text;
        }
    }

    record Escaped(ShortcodeMatch match) implements ShortcodeNode {
        public Escaped {
            Objects.requireNonNull(match, "match");
        }

        @Override
        public String source() {
            return match.whole();
        }
    }

    record Match(Shortcode shortcode) implements ShortcodeNode {
        public Match {
            Objects.requireNonNull(shortcode, "shortcode");
        }

        @Override
        public String source() {
            return shortcode.match().whole();
        }
    }
}
