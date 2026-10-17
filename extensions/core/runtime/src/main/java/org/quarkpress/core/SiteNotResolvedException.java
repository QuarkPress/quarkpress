package org.quarkpress.core;

/** Thrown when code asks for the current site and the request is not bound to one. */
public class SiteNotResolvedException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public SiteNotResolvedException(String message) {
        super(message);
    }
}
