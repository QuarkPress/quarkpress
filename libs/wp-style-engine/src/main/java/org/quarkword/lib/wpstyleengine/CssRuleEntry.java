package org.quarkword.lib.wpstyleengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.Objects;

/**
 * One entry of the CSS rules a stylesheet is built from, as WordPress's
 * {@code wp_style_engine_get_stylesheet_from_css_rules} takes them: a selector, declarations and an optional rules
 * group. The declarations come either raw, as the JSON value an entry holds (any value; only a non-empty array is
 * used), or as a {@link CssDeclarations} value. Immutable and thread-safe, provided
 * the caller does not change a raw JSON node after handing it over (it is kept, not copied).
 *
 * @param selector the selector, or {@code null} for none; an entry without a selector, or with {@code ""} or
 *     {@code "0"}, is skipped
 * @param rawDeclarations the raw declarations, any JSON value ({@code NullNode} for none); {@code null} when
 *     {@code declarations} is given
 * @param declarations the declarations as a value; {@code null} when {@code rawDeclarations} is given
 * @param rulesGroup the rules group, or {@code null} for none
 */
public record CssRuleEntry(String selector, JsonNode rawDeclarations, CssDeclarations declarations, String rulesGroup) {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /**
     * @throws IllegalArgumentException when not exactly one of {@code rawDeclarations} and {@code declarations} is
     *     given
     */
    public CssRuleEntry {
        if ((rawDeclarations == null) == (declarations == null)) {
            throw new IllegalArgumentException("exactly one of rawDeclarations and declarations must be given");
        }
    }

    /** An entry with raw declarations, property to value in map order, and no rules group. */
    public static CssRuleEntry of(String selector, Map<String, String> declarations) {
        return of(selector, declarations, null);
    }

    /** An entry with raw declarations, property to value in map order. */
    public static CssRuleEntry of(String selector, Map<String, String> declarations, String rulesGroup) {
        Objects.requireNonNull(declarations, "declarations");
        ObjectNode raw = NODES.objectNode();
        declarations.forEach((property, value) ->
                raw.put(Objects.requireNonNull(property, "property"), Objects.requireNonNull(value, "value")));
        return new CssRuleEntry(selector, raw, null, rulesGroup);
    }

    /** An entry with a declarations value, which is never skipped for being empty. */
    public static CssRuleEntry of(String selector, CssDeclarations declarations, String rulesGroup) {
        Objects.requireNonNull(declarations, "declarations");
        return new CssRuleEntry(selector, null, declarations, rulesGroup);
    }

    /**
     * An entry with raw JSON declarations, such as a theme.json value; {@code null} stands for JSON {@code null}.
     * The node is kept as it is, not copied, so it must not change afterwards.
     */
    public static CssRuleEntry fromJson(String selector, JsonNode declarations, String rulesGroup) {
        return new CssRuleEntry(
                selector, declarations == null ? NullNode.getInstance() : declarations, null, rulesGroup);
    }
}
