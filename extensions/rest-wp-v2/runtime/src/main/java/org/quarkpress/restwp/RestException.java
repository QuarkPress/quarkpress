package org.quarkpress.restwp;

import java.util.Objects;

/** A refusal thrown by a handler or a check; the REST server answers with its error. */
public final class RestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient RestError error;

    public RestException(RestError error) {
        super(Objects.requireNonNull(error, "error").code() + ": " + error.message());
        this.error = error;
    }

    public RestError error() {
        return error;
    }
}
