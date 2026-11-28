package org.quarkword.lib.wpstyleengine;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * A stylesheet from CSS rules, as {@code wp_style_engine_get_stylesheet_from_css_rules} builds it. Entries are
     * skipped when their selector is {@code null}, {@code ""} or {@code "0"}, or when their raw declarations are
     * PHP-empty or not an array; a declarations value is never skipped. Each other entry is also added to the
     * options' context store, when there is one, and the rules are then rendered by a new {@link StyleProcessor}.
     * The selectors and rules groups are used as given, neither trimmed nor escaped.
     *
     * @throws IllegalArgumentException when a context is given and a selector is empty after trimming
     */
    public static String stylesheetFromCssRules(List<CssRuleEntry> rules, StylesheetOptions options) {
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(options, "options");
        List<CssRule> built = new ArrayList<>(rules.size());
        for (CssRuleEntry entry : rules) {
            Objects.requireNonNull(entry, "entry");
            JsonNode raw = entry.rawDeclarations();
            if (Php.isEmpty(entry.selector()) || (raw != null && (Php.isEmpty(raw) || !Php.isArray(raw)))) {
                continue;
            }
            if (options.context() != null) {
                storeCssRule(options.context(), entry.selector(), raw, entry.declarations(), entry.rulesGroup());
            }
            CssDeclarations declarations = raw != null ? CssDeclarations.fromJson(raw) : entry.declarations();
            built.add(CssRule.of(entry.selector(), declarations, entry.rulesGroup()));
        }
        if (built.isEmpty()) {
            return "";
        }
        return new StyleProcessor().addRules(built).css(options.optimize(), options.prettify());
    }

    /** {@link #getStyles(JsonNode, StyleOptions)} with the default options. */
    public static StyleResult getStyles(JsonNode blockStyles) {
        return getStyles(blockStyles, StyleOptions.defaults());
    }

    /**
     * The CSS, declarations and class names of a block style object, as WordPress's
     * {@code wp_style_engine_get_styles} gives them. The groups and their properties are read in WordPress's table
     * order, whatever the order of the input. Presets ({@code var:preset|color|slug}) become custom properties and
     * class names. The declarations are returned raw; only the CSS is sanitised, so a value that renders nothing
     * (a number, a boolean, a nested array, rejected text) is in the declarations but not in the CSS.
     *
     * <p>Like WordPress, the CSS can carry what a value smuggles in: a {@code ;} inside a value starts another
     * declaration that passes the sanitiser on its own, and a background image URL is put in {@code url('...')}
     * without escaping.
     *
     * @param blockStyles the block style object; {@code null}, a non-array or an empty array gives
     *     {@link StyleResult#EMPTY}
     */
    public static StyleResult getStyles(JsonNode blockStyles, StyleOptions options) {
        Objects.requireNonNull(options, "options");
        BlockStyles.Parsed parsed = BlockStyles.parse(blockStyles, options.convertVarsToClassnames());
        String css = "";
        if (!parsed.declarations().isEmpty()) {
            com.fasterxml.jackson.databind.node.ObjectNode raw =
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            parsed.declarations().forEach(raw::set);
            css = compileCss(raw, options.selector());
        }
        String classnames = String.join(" ", new java.util.LinkedHashSet<>(parsed.classnames()));
        return new StyleResult(css, parsed.declarations(), classnames);
    }
}
