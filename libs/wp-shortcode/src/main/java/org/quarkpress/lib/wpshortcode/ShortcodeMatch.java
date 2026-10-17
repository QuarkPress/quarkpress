package org.quarkpress.lib.wpshortcode;

import java.util.List;
import java.util.Objects;

/**
 * The parts of one matched shortcode, as WordPress's match array holds them: the whole matched text, the
 * escape {@code [} (or ""), the name, the raw attribute text, the self-closing {@code /} (or ""), the enclosed
 * content ("" when not enclosing) and the closing escape {@code ]} (or "").
 */
public record ShortcodeMatch(
        String whole,
        String openEscape,
        String name,
        String attributeText,
        String selfClosingSlash,
        String content,
        String closeEscape) {

    public ShortcodeMatch {
        Objects.requireNonNull(whole, "whole");
        Objects.requireNonNull(openEscape, "openEscape");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(attributeText, "attributeText");
        Objects.requireNonNull(selfClosingSlash, "selfClosingSlash");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(closeEscape, "closeEscape");
    }

    /** True for {@code [[tag]]}: both escape brackets present, so the shortcode renders as literal text. */
    public boolean escaped() {
        return openEscape.equals("[") && closeEscape.equals("]");
    }

    /** The seven parts in WordPress's order. */
    public List<String> parts() {
        return List.of(whole, openEscape, name, attributeText, selfClosingSlash, content, closeEscape);
    }
}
