package org.quarkpress.restwp.runtime.wpv2;

import org.quarkpress.restwp.RouteRegistry;

/**
 * Routes a post type gets right after its own, from a module that may be absent (the revisions routes): the posts
 * controller reaches them through this type alone, so it loads and serves without that module.
 */
interface TypeRouteExtension {

    /** Registers the routes of {@code post}'s type. */
    void register(RouteRegistry routes, PostRoutes post);
}
