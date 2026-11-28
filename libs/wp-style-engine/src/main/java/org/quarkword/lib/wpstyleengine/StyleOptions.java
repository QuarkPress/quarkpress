package org.quarkword.lib.wpstyleengine;

import java.util.Objects;

/**
 * Options of {@link StyleEngine#getStyles(com.fasterxml.jackson.databind.JsonNode, StyleOptions)}. Immutable, but
 * the context store it holds, or the registry it names a store in, is not: an options value that has a context adds
 * to that store each time it is used.
 *
 * <p>The context is given either as a store ({@link #withContext(StyleStore)}) or as a store name in a registry
 * ({@link #withContext(StyleStores, String)}). The name form is WordPress's: the store is fetched, and created, only
 * when a rule is stored into it, so a call that stores nothing creates no store and the registry's
 * {@link StyleStores#names()} keep WordPress's order. Passing a store fetched up front creates it even when nothing
 * is stored, which can put it earlier in that order than WordPress would.
 *
 * @param selector the selector to wrap the CSS in, or {@code null} for bare declarations; {@code ""} and {@code "0"}
 *     count as none
 * @param context the store to add the declarations to, or {@code null} for none (or for the name form)
 * @param convertVarsToClassnames when true, presets give only their class names, and no custom property
 * @param contextStores the registry of the name form, or {@code null}; given together with {@code contextName}
 * @param contextName the store name of the name form, or {@code null}; never {@code ""} or {@code "0"}
 */
public record StyleOptions(
        String selector,
        StyleStore context,
        boolean convertVarsToClassnames,
        StyleStores contextStores,
        String contextName) {

    private static final StyleOptions DEFAULTS = new StyleOptions(null, null, false);

    /**
     * @throws IllegalArgumentException when only one of {@code contextStores} and {@code contextName} is given, when
     *     both a store and a name are given, or when the name is {@code ""} or {@code "0"}
     */
    public StyleOptions {
        if ((contextStores == null) != (contextName == null)) {
            throw new IllegalArgumentException("contextStores and contextName go together");
        }
        if (contextStores != null && context != null) {
            throw new IllegalArgumentException("a context store and a context name cannot both be given");
        }
        if (contextName != null && Php.isEmpty(contextName)) {
            throw new IllegalArgumentException("invalid style store name: \"" + contextName + "\"");
        }
    }

    /** Options with a store, or none, as the context. */
    public StyleOptions(String selector, StyleStore context, boolean convertVarsToClassnames) {
        this(selector, context, convertVarsToClassnames, null, null);
    }

    /** No selector, no store, presets turned into custom properties (var(--wp--preset--…)). */
    public static StyleOptions defaults() {
        return DEFAULTS;
    }

    public StyleOptions withSelector(String selector) {
        return new StyleOptions(selector, context, convertVarsToClassnames, contextStores, contextName);
    }

    /** These options with {@code context} as the store to add to; {@code null} for none. */
    public StyleOptions withContext(StyleStore context) {
        return new StyleOptions(selector, context, convertVarsToClassnames, null, null);
    }

    /**
     * These options with the store called {@code name} in {@code stores} as the context, fetched (and created) only
     * when a rule is stored into it, as WordPress's {@code context} option works.
     *
     * @throws IllegalArgumentException when {@code name} is {@code ""} or {@code "0"}
     */
    public StyleOptions withContext(StyleStores stores, String name) {
        Objects.requireNonNull(stores, "stores");
        Objects.requireNonNull(name, "name");
        return new StyleOptions(selector, null, convertVarsToClassnames, stores, name);
    }

    public StyleOptions withConvertVarsToClassnames(boolean convert) {
        return new StyleOptions(selector, context, convert, contextStores, contextName);
    }

    /** Whether a context is given, in either form. */
    boolean hasContext() {
        return context != null || contextStores != null;
    }

    /** The context store, fetched from the registry (and so created) in the name form. */
    StyleStore resolveContext() {
        return context != null ? context : contextStores.store(contextName);
    }
}
