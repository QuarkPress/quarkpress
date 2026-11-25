package org.quarkword.front;

import java.util.List;
import java.util.Map;

/**
 * Resolves a front-end request of the current site as WordPress's front controller does: the request's bounds, the
 * request parser, the conditional flags, the main query run as the viewer, the visibility gate with previews, and the
 * 404 decision. Variables reserved for what the front end does not serve yet (feeds, embeds, trackbacks, robots,
 * favicon, sitemaps, comment pages, REST routes and the sign-up pages) answer 404.
 */
public interface FrontResolver {

    /**
     * The request for {@code rawPath} (the request URI's path, percent-encoded as sent) with the decoded {@code query}
     * parameters (the last value of a repeated name counts), as {@code viewer} sees it.
     */
    FrontRequest resolve(String rawPath, Map<String, List<String>> query, Viewer viewer);
}
