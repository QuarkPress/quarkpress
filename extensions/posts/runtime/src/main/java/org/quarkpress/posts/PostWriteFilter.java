package org.quarkpress.posts;

import org.quarkpress.core.FilterSpi;

/**
 * Changes a post just before it is written (WordPress's {@code wp_insert_post_data}). Beans run in a chain, higher
 * {@code @Priority} first (no priority counts as 0; equal priorities by class name), which is the opposite of
 * WordPress, where lower numbers run first. Return the draft (changed or not); never null. A filter that throws stops
 * the write. A changed author must be an existing user; its profile is locked after the site's post lock, so the
 * write is refused at once when a transaction that waits for it holds that profile.
 */
@FilterSpi
@FunctionalInterface
public interface PostWriteFilter {

    PostDraft apply(PostDraft draft, PostWriteContext context);
}
