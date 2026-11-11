package org.quarkpress.outbound;

import java.io.IOException;
import java.util.Objects;

/** Why a fetch failed. */
public class FetchException extends IOException {

    private static final long serialVersionUID = 1L;

    /** WordPress's message for a URL its safe request refuses. */
    public static final String REFUSED_MESSAGE = "A valid URL was not provided.";

    /** The kinds of failure. */
    public enum Kind {
        /** The URL asked for is not one the fetcher may reach. */
        REFUSED_URL,
        /** A redirect points to a URL the fetcher may not reach. */
        REFUSED_REDIRECT,
        /** The host name did not resolve. */
        DNS,
        /** No connection could be made. */
        CONNECT,
        /** The TLS handshake failed, the certificate included. */
        TLS,
        /** The exchange took longer than allowed. */
        TIMEOUT,
        /** The body is larger than allowed; the connection was closed. */
        TOO_LARGE,
        /** Any other failure of the exchange. */
        IO
    }

    private final Kind kind;

    public FetchException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public FetchException(Kind kind, String message) {
        this(kind, message, null);
    }

    /** A refusal of a URL ({@link Kind#REFUSED_URL} or {@link Kind#REFUSED_REDIRECT}), with WordPress's message. */
    public static FetchException refused(Kind kind) {
        return new FetchException(kind, REFUSED_MESSAGE);
    }

    public Kind kind() {
        return kind;
    }
}
