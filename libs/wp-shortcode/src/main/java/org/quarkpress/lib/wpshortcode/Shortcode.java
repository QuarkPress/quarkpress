package org.quarkpress.lib.wpshortcode;

import java.util.Objects;

/**
 * One shortcode as handlers and hooks see it.
 *
 * @param tag the matched name
 * @param attributes the parsed attribute text
 * @param content the enclosed text, "" for standalone and self-closing shortcodes; brackets that the HTML pass
 *     hid appear as {@code &#91;}/{@code &#93;}, and come back when the output is unescaped
 * @param enclosing true when a closing tag was consumed (content may still be "")
 * @param selfClosing true for {@code [tag /]}
 * @param match the raw match parts
 */
public record Shortcode(
        String tag,
        ShortcodeAttributes attributes,
        String content,
        boolean enclosing,
        boolean selfClosing,
        ShortcodeMatch match) {

    public Shortcode {
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(attributes, "attributes");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(match, "match");
    }
}
