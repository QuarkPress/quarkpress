package org.quarkpress.lib.wpshortcode;

/** What a handler may ask of the processor that called it. Valid only during that call. */
public interface ShortcodeContext {

    /**
     * Runs the same processor on {@code content} (WordPress handlers call do_shortcode on their content to expand
     * nested shortcodes; output is never rescanned otherwise).
     *
     * @throws ShortcodeException when the expansion would nest deeper than {@link ShortcodeOptions#maxDepth()}
     */
    String expand(String content);

    /** 0 for handlers called by the outermost {@code apply}, one more for each nested {@link #expand}. */
    int depth();

    /**
     * True while the outermost {@code apply} that created this context is running: WordPress's
     * "do_shortcode" attachment-image context, for media code called from handlers.
     */
    boolean insideShortcodes();
}
