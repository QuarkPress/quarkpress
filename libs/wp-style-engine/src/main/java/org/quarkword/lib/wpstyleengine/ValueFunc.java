package org.quarkword.lib.wpstyleengine;

/** The custom handlers a block style definition can name in place of the default declaration rules. */
enum ValueFunc {
    /** {@code {"url": u}} becomes {@code url('u')}; a text value is used as it is. */
    URL_OR_VALUE,
    /** A map of per-side properties ({@code color}, {@code width}, ...) for one border side. */
    INDIVIDUAL_SIDES
}
