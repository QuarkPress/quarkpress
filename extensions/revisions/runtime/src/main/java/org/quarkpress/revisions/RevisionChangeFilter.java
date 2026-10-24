package org.quarkpress.revisions;

import org.quarkpress.core.FilterSpi;
import org.quarkpress.posts.PostSnapshot;

/**
 * Decides whether an update has changed the post since its latest regular revision, where WordPress's {@code
 * wp_save_post_revision_post_has_changed} runs; it runs after the built-in comparison of the revisioned fields
 * (whitespace normalized) and of the revisioned meta, and only when a regular revision exists.
 *
 * <p>Beans run in a chain, higher {@code @Priority} first (no priority counts as 0), which is the opposite of
 * WordPress, where lower numbers run first. Return a value; never null.
 */
@FilterSpi
@FunctionalInterface
public interface RevisionChangeFilter {

    boolean apply(boolean changed, RevisionSnapshot last, PostSnapshot current);
}
