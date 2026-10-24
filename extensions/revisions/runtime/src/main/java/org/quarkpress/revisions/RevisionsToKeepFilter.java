package org.quarkpress.revisions;

import org.quarkpress.core.FilterSpi;
import org.quarkpress.posts.PostSnapshot;

/**
 * Decides how many revisions of a post are kept, where WordPress's {@code wp_revisions_to_keep} runs: a negative number
 * keeps every revision, 0 saves none. It starts at the configured limit, or 0 for a type without revisions support (a
 * filter may re-enable such a type); the site's revisions feature, when off, makes the result 0 after the filters. One
 * filter sees every post; a rule per type reads the post's type.
 *
 * <p>Beans run in a chain, higher {@code @Priority} first (no priority counts as 0), which is the opposite of
 * WordPress, where lower numbers run first. Return a value; never null.
 */
@FilterSpi
@FunctionalInterface
public interface RevisionsToKeepFilter {

    int apply(int keep, PostSnapshot post);
}
