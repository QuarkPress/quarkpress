package org.quarkpress.lib.wpkses;

import java.util.List;
import java.util.Objects;

/**
 * One wp_kses call and the nested calls it makes for comments. Nested calls share a work budget, a depth limit
 * and a round limit (a documented difference from WordPress) so hostile input stays linear and every input ends.
 */
final class KsesRun {

    static final int MAX_DEPTH = 32;
    static final int MAX_ROUNDS = 64;
    static final long BUDGET_PER_INPUT_CHAR = 32;
    static final long MINIMUM_BUDGET = 1L << 20;
    /** Charged for every nested pass on top of its length, so the number of passes is bounded too. */
    static final long COST_PER_PASS = 16;

    private final KsesConfig config;
    private final AllowedHtml allowedHtml;
    private final Allowlist allowlist;
    private final List<String> protocols;
    private final boolean blockFilter;
    private final long[] budget;
    private final int depth;

    private KsesRun(
            KsesConfig config,
            AllowedHtml allowedHtml,
            Allowlist allowlist,
            List<String> protocols,
            boolean blockFilter,
            long[] budget,
            int depth) {
        this.config = config;
        this.allowedHtml = allowedHtml;
        this.allowlist = allowlist;
        this.protocols = protocols;
        this.blockFilter = blockFilter;
        this.budget = budget;
        this.depth = depth;
    }

    /** wp_kses(content, allowedHtml, protocols); an empty protocol list means the configured default. */
    static String sanitize(KsesConfig config, String content, AllowedHtml allowedHtml, List<String> protocols) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(allowedHtml, "allowedHtml");
        Objects.requireNonNull(protocols, "protocols");
        List<String> effective = protocols.isEmpty() ? config.protocols() : List.copyOf(protocols);
        Allowlist allowlist = switch (allowedHtml) {
            case Allowlist explicit -> explicit;
            case KsesContext context -> config.allowlists().apply(context);
        };
        String input = TextUtils.wellFormed(content);
        long[] budget = {Math.max(MINIMUM_BUDGET, BUDGET_PER_INPUT_CHAR * input.length())};
        return new KsesRun(config, allowedHtml, allowlist, effective, config.defaultPreFilters(), budget, 0)
                .kses(input);
    }

    /** Whether this run applies WordPress's block-attribute pre-filter. */
    boolean blockFilter() {
        return blockFilter;
    }

    Allowlist allowlist() {
        return allowlist;
    }

    List<String> protocols() {
        return protocols;
    }

    KsesConfig config() {
        return config;
    }

    /**
     * One pass: control characters removed, character references normalized, pre-filters applied, then every
     * token replaced. Control characters go first, so the attribute parser never sees a raw NUL.
     */
    String kses(String content) {
        String s = NullFilter.clean(content, true);
        s = EntityNormalizer.normalize(s, false);
        if (config.defaultPreFilters()) {
            s = PreFilters.lessThan(s);
            if (blockFilter) {
                s = PreFilters.blockAttributes(s, withoutBlockFilter());
            }
        }
        for (PreKsesFilter filter : config.preFilters()) {
            s = Objects.requireNonNull(filter.filter(s, allowedHtml, protocols), "a PreKsesFilter returned null");
        }
        return Tokenizer.rewrite(s, (token, out) -> out.append(TagHandler.handle(token, this)));
    }

    /**
     * kses applied to {@code content} until the output no longer changes, as WordPress does for the inside of a
     * comment. Null when that takes more than {@link #MAX_ROUNDS} rounds (WordPress loops forever on input such
     * as {@code <!-- <!a<b>> -->} without its default pre-filters), or when the depth limit or the shared work
     * budget is reached; the caller then drops the token.
     */
    String fixedPoint(String content) {
        if (depth >= MAX_DEPTH) {
            return null;
        }
        KsesRun nested = new KsesRun(config, allowedHtml, allowlist, protocols, blockFilter, budget, depth + 1);
        String current = content;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            budget[0] -= current.length() + COST_PER_PASS;
            if (budget[0] < 0) {
                return null;
            }
            String next = nested.kses(current);
            if (next.equals(current)) {
                return current;
            }
            current = next;
        }
        return null;
    }

    /** The same call without WordPress's block-attribute pre-filter, which removes itself while it runs. */
    KsesRun withoutBlockFilter() {
        return new KsesRun(config, allowedHtml, allowlist, protocols, false, budget, depth);
    }
}
