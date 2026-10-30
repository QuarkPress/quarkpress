package org.quarkpress.restwp;

/** Where {@link RestRouteProvider}s declare their routes. */
public interface RouteRegistry {

    /**
     * Starts a route of {@code namespace} (such as {@code wp/v2}); {@code pattern} is the part below the namespace, in
     * WordPress's form: literal text and the groups {@code (?P<name>[\d]+)} and {@code (?P<name>[\w-]+)}, such as
     * {@code /categories/(?P<id>[\d]+)}.
     *
     * @throws IllegalArgumentException when the pattern uses anything else
     */
    RouteBuilder route(String namespace, String pattern);
}
