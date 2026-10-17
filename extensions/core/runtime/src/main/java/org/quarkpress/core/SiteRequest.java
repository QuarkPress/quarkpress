package org.quarkpress.core;

import java.util.Objects;

/**
 * What a {@link SiteResolver} sees of an incoming request. {@link #none()} stands for work that has no request, such as
 * jobs and startup code; resolvers that need a host or a path answer empty for it.
 *
 * @param scheme {@code http} or {@code https}, empty when unknown
 * @param host the host name without port, empty when unknown
 * @param port the port, or -1 when unknown
 * @param path the request path, empty when unknown
 */
public record SiteRequest(String scheme, String host, int port, String path) {

    private static final SiteRequest NONE = new SiteRequest("", "", -1, "");

    public SiteRequest {
        Objects.requireNonNull(scheme, "scheme");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(path, "path");
        if (port < -1 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
    }

    public static SiteRequest none() {
        return NONE;
    }

    public boolean isNone() {
        return scheme.isEmpty() && host.isEmpty() && port == -1 && path.isEmpty();
    }
}
