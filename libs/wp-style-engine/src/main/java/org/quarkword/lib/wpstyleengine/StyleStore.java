package org.quarkword.lib.wpstyleengine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A named collection of CSS rules, as WordPress's {@code WP_Style_Engine_CSS_Rules_Store} keeps them. Rules are keyed
 * by their trimmed selector, or by the trimmed rules group, a space and the selector; adding to an existing key merges
 * the declarations into that rule, which keeps its position.
 *
 * <p>Mutable and not thread-safe: a store belongs to one caller, typically one request.
 */
public final class StyleStore {

    private final String name;
    private final LinkedHashMap<String, Entry> rules = new LinkedHashMap<>();

    /** A rule being collected: its selector, its group and its declarations so far. */
    private record Entry(String selector, String rulesGroup, CssDeclarations.Builder declarations) {}

    /**
     * @throws IllegalArgumentException when {@code name} is {@code ""} or {@code "0"}
     */
    public StyleStore(String name) {
        Objects.requireNonNull(name, "name");
        if (Php.isEmpty(name)) {
            throw new IllegalArgumentException("invalid style store name: \"" + name + "\"");
        }
        this.name = name;
    }

    public String name() {
        return name;
    }

    /**
     * Adds {@code declarations} to the rule for {@code selector} in {@code rulesGroup}, creating the rule at the end
     * when its key is new. Both texts are trimmed of space, TAB, LF, CR, NUL and VT; {@code "0"} counts as empty. The
     * arguments come in the order of {@link CssRule} and {@link CssRuleEntry}.
     *
     * @param rulesGroup the rules group, or {@code null} for none
     * @throws IllegalArgumentException when the selector is empty after trimming
     */
    public void addRule(String selector, CssDeclarations declarations, String rulesGroup) {
        Objects.requireNonNull(selector, "selector");
        Objects.requireNonNull(declarations, "declarations");
        String trimmedSelector = emptyIfFalsy(Php.trim(selector));
        if (trimmedSelector.isEmpty()) {
            throw new IllegalArgumentException("selector is empty after trimming: \"" + selector + "\"");
        }
        String group = rulesGroup == null ? "" : emptyIfFalsy(Php.trim(rulesGroup));
        String key = group.isEmpty() ? trimmedSelector : group + " " + trimmedSelector;
        rules.computeIfAbsent(key, k -> new Entry(trimmedSelector, group, CssDeclarations.builder()))
                .declarations()
                .addAll(declarations);
    }

    private static String emptyIfFalsy(String s) {
        return s.equals("0") ? "" : s;
    }

    /** Removes the rule stored under exactly {@code key}, which is not trimmed. */
    public void removeRule(String key) {
        Objects.requireNonNull(key, "key");
        rules.remove(key);
    }

    /** A snapshot of the rules by key, in insertion order; unmodifiable. */
    public Map<String, CssRule> rules() {
        LinkedHashMap<String, CssRule> snapshot = new LinkedHashMap<>();
        rules.forEach((key, entry) -> snapshot.put(
                key, CssRule.of(entry.selector(), entry.declarations().build(), entry.rulesGroup())));
        return Collections.unmodifiableMap(snapshot);
    }

    @Override
    public String toString() {
        return "StyleStore[" + name + ", " + rules.size() + " rules]";
    }
}
