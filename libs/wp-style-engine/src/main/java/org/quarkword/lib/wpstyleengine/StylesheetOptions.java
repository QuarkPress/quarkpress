package org.quarkword.lib.wpstyleengine;

import java.util.Objects;

/**
 * Options for building a stylesheet from CSS rules. Immutable; the context store it holds, or the registry it names a
 * store in, is not.
 *
 * <p>As in {@link StyleOptions}, the context is given either as a store or as a store name in a registry; the name
 * form fetches, and creates, the store only when a rule is stored into it, as WordPress does.
 *
 * @param context the store each rule is also added to, or {@code null} for none (or for the name form)
 * @param optimize whether rules with identical declarations are combined under one selector list
 * @param prettify whether the stylesheet is indented, one declaration per line
 * @param contextStores the registry of the name form, or {@code null}; given together with {@code contextName}
 * @param contextName the store name of the name form, or {@code null}; never {@code ""} or {@code "0"}
 */
public record StylesheetOptions(
        StyleStore context, boolean optimize, boolean prettify, StyleStores contextStores, String contextName) {

    private static final StylesheetOptions DEFAULTS = new StylesheetOptions(null, false, false);

    /**
     * @throws IllegalArgumentException when only one of {@code contextStores} and {@code contextName} is given, when
     *     both a store and a name are given, or when the name is {@code ""} or {@code "0"}
     */
    public StylesheetOptions {
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
    public StylesheetOptions(StyleStore context, boolean optimize, boolean prettify) {
        this(context, optimize, prettify, null, null);
    }

    /** No context, not optimized, not prettified: WordPress's defaults when {@code SCRIPT_DEBUG} is off. */
    public static StylesheetOptions defaults() {
        return DEFAULTS;
    }

    /** These options with {@code store} as the context; {@code null} for none. */
    public StylesheetOptions withContext(StyleStore store) {
        return new StylesheetOptions(store, optimize, prettify, null, null);
    }

    /**
     * These options with the store called {@code name} in {@code stores} as the context, fetched (and created) only
     * when a rule is stored into it, as WordPress's {@code context} option works.
     *
     * @throws IllegalArgumentException when {@code name} is {@code ""} or {@code "0"}
     */
    public StylesheetOptions withContext(StyleStores stores, String name) {
        Objects.requireNonNull(stores, "stores");
        Objects.requireNonNull(name, "name");
        return new StylesheetOptions(null, optimize, prettify, stores, name);
    }

    public StylesheetOptions withOptimize(boolean optimize) {
        return new StylesheetOptions(context, optimize, prettify, contextStores, contextName);
    }

    public StylesheetOptions withPrettify(boolean prettify) {
        return new StylesheetOptions(context, optimize, prettify, contextStores, contextName);
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
