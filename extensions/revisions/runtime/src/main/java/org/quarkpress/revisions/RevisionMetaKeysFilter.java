package org.quarkpress.revisions;

import java.util.Set;
import org.quarkpress.core.FilterSpi;

/**
 * Decides the post meta keys copied into revisions, compared by the change check, autosaved and restored, by post
 * type, where WordPress's {@code wp_post_revision_meta_keys} runs. The keys given are those the posts module's meta
 * registry keeps revisions for: {@code footnotes} for every REST type that supports an editor, custom fields and
 * revisions, and every registered key with revisions enabled.
 *
 * <p>Beans run in a chain, higher {@code @Priority} first (no priority counts as 0), which is the opposite of
 * WordPress, where lower numbers run first. Return a value; never null.
 */
@FilterSpi
@FunctionalInterface
public interface RevisionMetaKeysFilter {

    Set<String> apply(Set<String> keys, String type);
}
