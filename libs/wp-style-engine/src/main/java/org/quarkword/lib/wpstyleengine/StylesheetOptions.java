package org.quarkword.lib.wpstyleengine;

/**
 * Options for building a stylesheet from CSS rules. Immutable; the context store it names is not.
 *
 * @param context the store each rule is also added to, or {@code null} for none
 * @param optimize whether rules with identical declarations are combined under one selector list
 * @param prettify whether the stylesheet is indented, one declaration per line
 */
public record StylesheetOptions(StyleStore context, boolean optimize, boolean prettify) {

    private static final StylesheetOptions DEFAULTS = new StylesheetOptions(null, false, false);

    /** No context, not optimized, not prettified: WordPress's defaults when {@code SCRIPT_DEBUG} is off. */
    public static StylesheetOptions defaults() {
        return DEFAULTS;
    }

    /** These options with {@code store} as the context; {@code null} for none. */
    public StylesheetOptions withContext(StyleStore store) {
        return new StylesheetOptions(store, optimize, prettify);
    }

    public StylesheetOptions withOptimize(boolean optimize) {
        return new StylesheetOptions(context, optimize, prettify);
    }

    public StylesheetOptions withPrettify(boolean prettify) {
        return new StylesheetOptions(context, optimize, prettify);
    }
}
