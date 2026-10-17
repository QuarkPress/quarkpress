package org.quarkpress.core;

/**
 * Transforms rendered post content. Beans run in a chain, higher {@code @Priority} first (no priority counts as 0), equal priorities by fully qualified class name (see {@link Filters#ordered});
 * this is the opposite of WordPress, where lower numbers run first. Return a new value; never return null.
 */
@FilterSpi
@FunctionalInterface
public interface ContentFilter {

    String apply(String html, RenderContext ctx);
}
