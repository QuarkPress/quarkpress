package org.quarkpress.posts;

import org.quarkpress.core.FilterSpi;

/**
 * Changes the slug a post gets after uniqueness was decided (WordPress's {@code wp_unique_post_slug} filter); not
 * called for draft, pending and auto-draft posts, whose slugs are not made unique. {@code context.original()} is the
 * sanitized slug before uniqueness, not the raw text a {@link SlugSanitizer} sees there. Beans run in a chain, higher
 * {@code @Priority} first, the opposite of WordPress. Never return null.
 */
@FilterSpi
@FunctionalInterface
public interface PostSlugFilter {

    String apply(String uniqueSlug, SlugContext context);
}
