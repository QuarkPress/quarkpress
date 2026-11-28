package org.quarkword.lib.wpstyleengine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WordPress's stylesheet optimisation: rules whose declarations are identical (the same properties with the same raw
 * values and the same important flags, in any order) are replaced by one rule for their selectors joined with
 * {@code ","}, added after the others, or in place of an existing rule with that selector list. As in WordPress,
 * rules are compared by selector, not by key, so a rule in a rules group takes part under its bare selector, and the
 * combined rule takes its declarations from the rule stored under the first selector's text and has no rules group.
 *
 * <p>When no rule is stored under that text (every rule with that selector is in a rules group), the declarations
 * of the first rule with that selector are used. Linear in the number of rules plus their declarations (each rule's
 * declarations are sorted once).
 */
final class Optimizer {

    private Optimizer() {}

    /** What makes two rules combinable: their declarations sorted by property, and their important properties sorted. */
    private record Identity(List<String> declarations, List<String> important) {

        static Identity of(CssDeclarations declarations) {
            List<String> sorted = new ArrayList<>(declarations.declarations().size() * 2);
            declarations.declarations().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> {
                        sorted.add(e.getKey());
                        sorted.add(e.getValue());
                    });
            List<String> important = new ArrayList<>(declarations.important());
            important.sort(null);
            return new Identity(sorted, important);
        }
    }

    /** Combines the rules in place; {@code rules} maps each key to its rule, in processor order. */
    static void combine(LinkedHashMap<String, CssRule> rules) {
        // selector -> identity; a later rule with the same selector replaces the identity and keeps the position
        LinkedHashMap<String, Identity> bySelector = new LinkedHashMap<>();
        HashMap<String, CssRule> firstBySelector = new HashMap<>();
        for (CssRule rule : rules.values()) {
            bySelector.put(rule.selector(), Identity.of(rule.declarations()));
            firstBySelector.putIfAbsent(rule.selector(), rule);
        }
        HashMap<Identity, List<String>> selectors = new HashMap<>();
        bySelector.forEach((selector, identity) ->
                selectors.computeIfAbsent(identity, k -> new ArrayList<>(1)).add(selector));
        for (Identity identity : bySelector.values()) {
            List<String> duplicates = selectors.remove(identity);
            if (duplicates == null || duplicates.size() < 2) {
                continue;
            }
            String first = duplicates.get(0);
            CssRule source = rules.get(first);
            CssDeclarations declarations = (source != null ? source : firstBySelector.get(first)).declarations();
            for (String selector : duplicates) {
                rules.remove(selector);
            }
            String combined = String.join(",", duplicates);
            // an existing rule under the combined text is replaced where it stands
            rules.put(combined, CssRule.of(combined, declarations));
        }
    }
}
