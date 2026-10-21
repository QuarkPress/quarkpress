package org.quarkpress.taxonomy;

import org.quarkpress.core.FilterSpi;

/**
 * Changes a term just before it is written: where WordPress's {@code wp_insert_term_data} and {@code
 * wp_update_term_data} run, on the sanitized term after the duplicate-name check, with its slug made unique (the
 * draft also carries the description and the parent, which WordPress filters elsewhere). Beans run in a chain,
 * higher {@code @Priority} first (no priority counts as 0), which is the opposite of WordPress, where lower numbers
 * run first. Return the draft (changed or not); never null. A filter that throws stops the write. A filter runs
 * before the term is written, so when it throws an {@code IllegalArgumentException} the caller's transaction is not
 * marked for rollback, and database writes the filter made itself stay in it. What a filter returns is checked
 * again: a parent must be a term of the same taxonomy and site (and only hierarchical taxonomies take one), a changed
 * slug must not be held by another term of the taxonomy, and values too long for their column are refused.
 */
@FilterSpi
@FunctionalInterface
public interface TermWriteFilter {

    TermDraft apply(TermDraft draft, TermWriteContext context);
}
