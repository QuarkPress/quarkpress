package org.quarkpress.posts;

/**
 * Which save filters apply to the caller: kses for a caller without {@code unfiltered_html}, block custom-CSS
 * stripping for a caller without {@code edit_css}.
 */
public record SaveFilters(boolean kses, boolean stripCustomCss) {

    /** A caller who may save any HTML and CSS. */
    public static final SaveFilters NONE = new SaveFilters(false, false);
}
