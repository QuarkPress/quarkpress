package org.quarkword.front.runtime;

import java.util.Objects;

/**
 * The address a front-end request was sent to, as WordPress rebuilds it for its redirects: the method, the scheme
 * (https when the request is, after the proxy headers the server trusts), the {@code Host} header as sent (a port
 * included), and the request target as sent (path and query, percent-encoded).
 */
public record RequestUrl(String method, String scheme, String host, String target) {

    public RequestUrl {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(scheme, "scheme");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(target, "target");
    }

    /** A GET of {@code target} on {@code http://host}. */
    public static RequestUrl get(String host, String target) {
        return new RequestUrl("GET", "http", host, target);
    }

    /** {@code scheme://host target}. */
    public String url() {
        return scheme + "://" + host + target;
    }

    /** The query of the target (after the first {@code ?}, before any {@code #}), empty when there is none. */
    public String query() {
        int question = target.indexOf('?');
        if (question < 0) {
            return "";
        }
        int hash = target.indexOf('#', question);
        return hash < 0 ? target.substring(question + 1) : target.substring(question + 1, hash);
    }
}
