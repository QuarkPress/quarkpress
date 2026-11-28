package org.quarkword.lib.wpstyleengine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds a stylesheet from CSS rules and style stores, as WordPress's {@code WP_Style_Engine_Processor} does. Rules
 * added directly come first, in order, then the rules of each store in the order the stores were added. Rules are
 * keyed by their selector as given (not trimmed), or by their rules group, a space and the selector; a rule whose key
 * is already taken is merged into the earlier one, which keeps its position and takes the later values.
 *
 * <p>The rules and stores given are never changed, and {@link #css(boolean, boolean)} reads the stores as they are at
 * the time of the call: calling it again gives the same text while nothing is added.
 *
 * <p>Mutable and not thread-safe: a processor belongs to one caller.
 */
public final class StyleProcessor {

    private final LinkedHashMap<String, StyleStore> stores = new LinkedHashMap<>();
    private final List<CssRule> rules = new ArrayList<>();

    /** Adds a store; a store with the same name as one added before takes its place. */
    public StyleProcessor addStore(StyleStore store) {
        Objects.requireNonNull(store, "store");
        stores.put(store.name(), store);
        return this;
    }

    public StyleProcessor addRules(CssRule... rules) {
        Objects.requireNonNull(rules, "rules");
        return addRules(List.of(rules));
    }

    public StyleProcessor addRules(List<CssRule> rules) {
        Objects.requireNonNull(rules, "rules");
        for (CssRule rule : rules) {
            this.rules.add(Objects.requireNonNull(rule, "rule"));
        }
        return this;
    }

    /** The stylesheet on one line, not optimized. */
    public String css() {
        return css(false, false);
    }

    /**
     * The stylesheet. Optimized, rules with identical declarations are combined into one rule for their selectors,
     * placed after the others or in place of an existing rule with that selector list. Prettified, each rule is
     * indented and followed by a newline, also a rule that renders empty because every declaration was rejected.
     */
    public String css(boolean optimize, boolean prettify) {
        LinkedHashMap<String, Merged> merged = new LinkedHashMap<>();
        for (CssRule rule : rules) {
            merge(merged, rule);
        }
        for (StyleStore store : stores.values()) {
            for (CssRule rule : store.rules().values()) {
                merge(merged, rule);
            }
        }
        LinkedHashMap<String, CssRule> working = new LinkedHashMap<>();
        merged.forEach((key, m) -> working.put(key, m.rule()));
        if (optimize) {
            Optimizer.combine(working);
        }
        StringBuilder css = new StringBuilder();
        for (CssRule rule : working.values()) {
            css.append(rule.toCss(prettify, 0));
            if (prettify) {
                css.append('\n');
            }
        }
        return css.toString();
    }

    private static void merge(Map<String, Merged> merged, CssRule rule) {
        String key = Php.isEmpty(rule.rulesGroup()) ? rule.selector() : rule.rulesGroup() + " " + rule.selector();
        Merged existing = merged.get(key);
        if (existing == null) {
            merged.put(key, new Merged(rule));
        } else {
            existing.add(rule.declarations());
        }
    }

    /** The first rule under a key, with the declarations of later rules under the same key merged in. */
    private static final class Merged {

        private final CssRule first;
        private CssDeclarations.Builder declarations;

        Merged(CssRule first) {
            this.first = first;
        }

        void add(CssDeclarations more) {
            if (declarations == null) {
                declarations = first.declarations().toBuilder();
            }
            declarations.addAll(more);
        }

        CssRule rule() {
            return declarations == null
                    ? first
                    : CssRule.of(first.selector(), declarations.build(), first.rulesGroup());
        }
    }
}
