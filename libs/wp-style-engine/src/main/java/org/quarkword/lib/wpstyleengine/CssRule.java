package org.quarkword.lib.wpstyleengine;

import java.util.Objects;

/**
 * A CSS rule as WordPress's {@code WP_Style_Engine_CSS_Rule} renders it: a selector, its declarations and an optional
 * rules group, an at-rule such as {@code @media print} or a nesting parent. The selector and the group are used as
 * they are, neither trimmed nor escaped; the group {@code ""} or {@code "0"} means no group. Immutable and
 * thread-safe.
 *
 * @param selector the selector, possibly empty
 * @param declarations the declarations
 * @param rulesGroup the rules group; {@code null} becomes {@code ""}
 */
public record CssRule(String selector, CssDeclarations declarations, String rulesGroup) {

    public CssRule {
        Objects.requireNonNull(selector, "selector");
        Objects.requireNonNull(declarations, "declarations");
        if (rulesGroup == null) {
            rulesGroup = "";
        }
    }

    public static CssRule of(String selector, CssDeclarations declarations) {
        return new CssRule(selector, declarations, "");
    }

    public static CssRule of(String selector, CssDeclarations declarations, String rulesGroup) {
        return new CssRule(selector, declarations, rulesGroup);
    }

    /**
     * This rule with {@code more} merged in: each declaration in order with its own important flag; a property the
     * rule already has keeps its position and takes the new value.
     */
    public CssRule withDeclarations(CssDeclarations more) {
        return new CssRule(selector, declarations.withAll(more), rulesGroup);
    }

    /** The rule on one line, {@code .a{color:red;}}; {@code ""} when every declaration is rejected. */
    public String toCss() {
        return toCss(false, 0);
    }

    /**
     * The rule as CSS text; {@code ""} when every declaration is rejected. Prettified, the rule sits behind
     * {@code indent} tabs, each selector of a list goes on its own line after its comma (every comma counts, also
     * one inside {@code :is(...)}), and the declarations go one per line, one level deeper than the selector.
     *
     * @throws IllegalArgumentException when prettifying with a negative {@code indent}
     */
    public String toCss(boolean prettify, int indent) {
        boolean grouped = !Php.isEmpty(rulesGroup);
        String ruleIndent = prettify ? "\t".repeat(indent) : "";
        String nestedIndent = prettify ? "\t".repeat(indent + 1) : "";
        String css = declarations.toCss(prettify, indent + (grouped ? 2 : 1));
        if (css.isEmpty()) {
            return "";
        }
        String suffix = prettify ? "\n" : "";
        String spacer = prettify ? " " : "";
        String text = prettify ? prettySelector(selector) : selector;
        StringBuilder out = new StringBuilder(ruleIndent);
        if (grouped) {
            out.append(rulesGroup).append(spacer).append('{').append(suffix).append(nestedIndent);
        }
        out.append(text).append(spacer).append('{').append(suffix).append(css).append(suffix);
        if (grouped) {
            out.append(nestedIndent)
                    .append('}')
                    .append(suffix)
                    .append(ruleIndent)
                    .append('}');
        } else {
            out.append(ruleIndent).append('}');
        }
        return out.toString();
    }

    /** Every comma-separated part trimmed, rejoined with a comma and a newline. */
    private static String prettySelector(String selector) {
        StringBuilder out = new StringBuilder(selector.length() + 16);
        int start = 0;
        while (true) {
            int comma = selector.indexOf(',', start);
            int end = comma < 0 ? selector.length() : comma;
            out.append(Php.trim(selector.substring(start, end)));
            if (comma < 0) {
                return out.toString();
            }
            out.append(",\n");
            start = comma + 1;
        }
    }
}
