package org.quarkpress.restwp.runtime;

import org.quarkpress.restwp.RestRequest;

/**
 * A request the REST server matched to a route and an endpoint: it knows the route's URL and the endpoint's declared
 * arguments. Tests of handlers implement it on their stub requests.
 */
public interface ServedRequest extends RestRequest {

    /**
     * The matched route's URL for this request: the route's pattern with each named group replaced by the value the
     * request gave it, below the site's API root (as WordPress names a collection in its pagination links).
     */
    String routeUrl();

    /** Whether the matched endpoint declares an argument named {@code name}. */
    boolean declaresArg(String name);
}
