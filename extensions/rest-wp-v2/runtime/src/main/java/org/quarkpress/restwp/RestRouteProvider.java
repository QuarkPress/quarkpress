package org.quarkpress.restwp;

/**
 * Declares REST routes. Every CDI bean implementing it is asked once, when the REST server first serves a request;
 * providers run by {@link #order()} (then by class name), and the namespaces and routes they register keep that order.
 */
public interface RestRouteProvider {

    void register(RouteRegistry routes);

    /** Lower runs first; WordPress's own routes use the default. */
    default int order() {
        return 1000;
    }
}
