package org.quarkpress.core;

/**
 * Transforms a rendered post title. Beans run in a chain, higher {@code @Priority} first (no priority counts as 0), equal priorities by fully qualified class name (see {@link Filters#ordered});
 * this is the opposite of WordPress, where lower numbers run first. Return a new value; never return null.
 */
@FilterSpi
@FunctionalInterface
public interface TitleFilter {

    String apply(String title, RenderContext ctx);
}
