package org.quarkpress.posts;

import org.quarkpress.core.FilterSpi;

/**
 * Changes a query before it runs (WordPress's {@code pre_get_posts}). Beans run in a chain, higher {@code @Priority}
 * first, the opposite of WordPress. Return a query (changed or not); never null.
 */
@FilterSpi
@FunctionalInterface
public interface PostQueryFilter {

    PostQuery apply(PostQuery query, QueryContext context);
}
