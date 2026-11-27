package org.quarkword.lib.wpformatting;

import java.util.Objects;

/** How {@code capital_P_dangit} corrects the spelling "Wordpress". */
public enum CapitalPMode {
    /** Every occurrence is corrected: the {@code the_title} and {@code wp_title} filters. */
    SIMPLE,
    /**
     * Only occurrences after a space, {@code >}, {@code (} or an opening curly quote entity are corrected: every other
     * filter and direct calls.
     */
    JUDICIOUS;

    /** The mode WordPress uses when the current filter is {@code filterName} (exact, case-sensitive). */
    public static CapitalPMode forFilter(String filterName) {
        Objects.requireNonNull(filterName, "filterName");
        return filterName.equals("the_title") || filterName.equals("wp_title") ? SIMPLE : JUDICIOUS;
    }
}
