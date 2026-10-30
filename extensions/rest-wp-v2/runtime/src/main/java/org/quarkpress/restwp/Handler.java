package org.quarkpress.restwp;

/** What an endpoint does once its arguments are checked and its permission granted. */
@FunctionalInterface
public interface Handler {

    /**
     * The answer to {@code request}; a refusal is thrown as {@link RestException}.
     */
    RestResponse handle(RestRequest request);
}
