package org.quarkpress.revisions;

import java.util.Set;
import org.quarkpress.core.FilterSpi;
import org.quarkpress.posts.PostSnapshot;

/**
 * Decides the fields a revision copies, compares and restores, where WordPress's {@code _wp_post_revision_fields}
 * runs; a filter may leave fields out. It runs for the save, the change check, the autosave comparison and the restore
 * (with the post being restored).
 *
 * <p>Beans run in a chain, higher {@code @Priority} first (no priority counts as 0), which is the opposite of
 * WordPress, where lower numbers run first. Return a value; never null.
 */
@FilterSpi
@FunctionalInterface
public interface RevisionFieldsFilter {

    Set<RevisionField> apply(Set<RevisionField> fields, PostSnapshot post);
}
