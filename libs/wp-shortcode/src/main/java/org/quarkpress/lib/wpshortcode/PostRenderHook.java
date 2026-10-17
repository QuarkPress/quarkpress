package org.quarkpress.lib.wpshortcode;

/** WordPress's do_shortcode_tag filter. */
@FunctionalInterface
public interface PostRenderHook {

    /** Receives the rendering with the escape brackets already added; a null result renders as "". */
    String after(String output, Shortcode shortcode);
}
