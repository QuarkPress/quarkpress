package org.quarkpress.lib.wpshortcode;

/** Renders one shortcode; the counterpart of a WordPress shortcode callback. */
@FunctionalInterface
public interface ShortcodeHandler {

    /**
     * Returns the replacement text for {@code shortcode}. A null result renders as the empty string. Exceptions
     * propagate to the caller of {@link Shortcodes#apply(String)} unchanged.
     */
    String render(Shortcode shortcode, ShortcodeContext context);
}
