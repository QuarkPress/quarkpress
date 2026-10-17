package org.quarkpress.lib.wpshortcode;

import java.util.Optional;

/**
 * Cleans one HTML attribute after shortcodes in its quoted value were expanded (WordPress runs
 * wp_kses_one_attr there). {@code BasicAttributeSanitizer} is the default.
 */
public interface HtmlAttributeSanitizer {

    /**
     * Returns the sanitized attribute token (leading and trailing whitespace kept), or empty to keep the
     * original token, shortcode text included.
     *
     * @param elementName the element name as written, such as {@code a} or {@code IMG}
     * @param attributeToken the attribute after expansion, such as {@code  title="red" }
     */
    Optional<String> sanitize(String elementName, String attributeToken);
}
