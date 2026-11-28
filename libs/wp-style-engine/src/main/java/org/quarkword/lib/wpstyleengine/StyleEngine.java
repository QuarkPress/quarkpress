package org.quarkword.lib.wpstyleengine;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/** WordPress's style engine: block style declarations and class names, CSS rules, stores and stylesheets. */
public final class StyleEngine {

    private StyleEngine() {}

    /**
     * The declarations as CSS: a rule {@code selector{...}} when the selector is given, otherwise the bare declarations.
     * A selector of {@code ""} or {@code "0"} counts as none. No declarations give {@code ""}.
     *
     * @param selector the selector, or {@code null} for none
     */
    public static String compileCss(CssDeclarations declarations, String selector) {
        Objects.requireNonNull(declarations, "declarations");
        if (declarations.isEmpty()) {
            return "";
        }
        if (!Php.isEmpty(selector)) {
            return CssRule.of(selector, declarations).toCss();
        }
        return declarations.toCss();
    }

    /** {@link #compileCss(CssDeclarations, String)} on raw JSON declarations; anything but a non-empty array gives "". */
    static String compileCss(JsonNode rawDeclarations, String selector) {
        if (!Php.isArray(rawDeclarations) || Php.isEmpty(rawDeclarations)) {
            return "";
        }
        return compileCss(CssDeclarations.fromJson(rawDeclarations), selector);
    }

    /**
     * Adds declarations to the rule for {@code selector} in {@code store}, given either raw JSON or as an object.
     * Nothing happens when the selector is {@code null}, {@code ""} or {@code "0"}, or when the raw declarations are
     * PHP-empty.
     *
     * @throws IllegalArgumentException when not exactly one of {@code rawDeclarations} and {@code object} is given,
     *     when the raw declarations are not an array, or when the selector is empty after trimming
     */
    static void storeCssRule(
            StyleStore store, String selector, JsonNode rawDeclarations, CssDeclarations object, String rulesGroup) {
        Objects.requireNonNull(store, "store");
        if ((rawDeclarations == null) == (object == null)) {
            throw new IllegalArgumentException("exactly one of the raw and the object declarations must be given");
        }
        if (Php.isEmpty(selector) || (object == null && Php.isEmpty(rawDeclarations))) {
            return;
        }
        if (object == null && !Php.isArray(rawDeclarations)) {
            throw new IllegalArgumentException("raw declarations are not an array: " + rawDeclarations.getNodeType());
        }
        store.addRule(selector, rulesGroup, object != null ? object : CssDeclarations.fromJson(rawDeclarations));
    }
}
