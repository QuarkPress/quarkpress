package org.quarkword.lib.wpstyleengine;

/**
 * Options of {@link StyleEngine#getStyles(com.fasterxml.jackson.databind.JsonNode, StyleOptions)}.
 *
 * @param selector the selector to wrap the CSS in, or {@code null} for bare declarations; {@code ""} and {@code "0"}
 *     count as none
 * @param context the store to add the declarations to, or {@code null} for none
 * @param convertVarsToClassnames when true, presets give only their class names, and no custom property
 */
public record StyleOptions(String selector, StyleStore context, boolean convertVarsToClassnames) {

    private static final StyleOptions DEFAULTS = new StyleOptions(null, null, false);

    /** No selector, no store, presets turned into custom properties (var(--wp--preset--…)). */
    public static StyleOptions defaults() {
        return DEFAULTS;
    }

    public StyleOptions withSelector(String selector) {
        return new StyleOptions(selector, context, convertVarsToClassnames);
    }

    public StyleOptions withContext(StyleStore context) {
        return new StyleOptions(selector, context, convertVarsToClassnames);
    }

    public StyleOptions withConvertVarsToClassnames(boolean convert) {
        return new StyleOptions(selector, context, convert);
    }
}
