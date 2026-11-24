package org.quarkword.posts;

import org.quarkword.core.FilterSpi;

/**
 * Changes a query before it runs (WordPress's {@code pre_get_posts}). Beans run in a chain, higher {@code @Priority}
 * first, the opposite of WordPress. Return a query (changed or not); never null.
 */
@FilterSpi
@FunctionalInterface
public interface PostQueryFilter {

    PostQuery apply(PostQuery query, QueryContext context);
}
