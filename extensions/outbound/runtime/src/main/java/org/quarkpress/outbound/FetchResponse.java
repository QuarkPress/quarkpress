package org.quarkpress.outbound;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The answer of a fetch: status, headers and a body read as it arrives. Close it when done; an unread body closes the
 * connection.
 */
public final class FetchResponse implements AutoCloseable {

    private final int status;
    private final String reason;
    private final Map<String, List<String>> headers;
    private final URI url;
    private final InputStream body;
    private final Closeable connection;

    FetchResponse(
            int status,
            String reason,
            Map<String, List<String>> headers,
            URI url,
            InputStream body,
            Closeable connection) {
        this.status = status;
        this.reason = Objects.requireNonNullElse(reason, "");
        TreeMap<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach((name, values) -> copy.merge(name, List.copyOf(values), (a, b) -> {
            List<String> all = new ArrayList<>(a);
            all.addAll(b);
            return List.copyOf(all);
        }));
        this.headers = copy;
        this.url = url;
        this.body = body;
        this.connection = connection;
    }

    /** The HTTP status code. */
    public int status() {
        return status;
    }

    /** The reason phrase of the status line. */
    public String reason() {
        return reason;
    }

    /** The values of the header {@code name} (any case), in order; empty when absent. */
    public List<String> header(String name) {
        return headers.getOrDefault(name, List.of());
    }

    /** The URL that answered: the one asked for, or the last redirect's target. */
    public URI url() {
        return url;
    }

    /**
     * The body as it arrives (decompressed). Reading blocks; beyond the byte limit the connection is closed and the
     * read throws a {@link FetchException} of kind {@link FetchException.Kind#TOO_LARGE}, past the deadline one of
     * kind {@link FetchException.Kind#TIMEOUT}.
     */
    public InputStream body() {
        return body;
    }

    /** Closes the connection unless the body was read to its end. */
    @Override
    public void close() throws IOException {
        connection.close();
    }
}
