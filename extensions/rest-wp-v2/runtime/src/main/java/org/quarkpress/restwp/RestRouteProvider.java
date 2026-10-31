package org.quarkpress.restwp;

/**
 * Declares REST routes. Every CDI bean implementing it is asked once, while the application starts; providers run by
 * {@link #order()} (then by class name), and the namespaces and routes they register keep that order. A registration
 * the REST server refuses fails the start.
 */
public interface RestRouteProvider {

    void register(RouteRegistry routes);

    /** Lower runs first; WordPress's own routes use orders 0 to 600, and the default puts a provider after them. */
    default int order() {
        return 1000;
    }
}
