package org.quarkpress.outbound;

import io.quarkus.runtime.BlockingOperationControl;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.quarkpress.outbound.runtime.AddressPolicy;
import org.quarkpress.outbound.runtime.OutboundConfig;
import org.quarkpress.outbound.runtime.VertxFetch;

/**
 * A GET that reaches public addresses only, for URLs the server is asked to fetch (oEmbed, uploads from a URL).
 *
 * <p>A URL may be fetched when its scheme is {@code http} or {@code https}, it names a host without user info, its
 * host is a name or an IPv4 address (no IPv6 literal), its port (or its scheme's) is among
 * {@code quarkpress.outbound.allowed-ports}, and every address its host resolves to is public. Each hop resolves the
 * host once and connects to the first of the checked addresses, so a host that resolves elsewhere a moment later is
 * not reached there; the {@code Host} header, TLS SNI and certificate verification still use the URL's host.
 * Redirects (301, 302, 303, 307, 308) are followed by hand, each target checked as a new URL; after the redirect
 * budget the last 3xx answer is returned as is. Bodies are decompressed and the byte limit counts decompressed bytes.
 *
 * <p>Both {@link #allowed} and {@link #get} block (host resolution, the exchange) and refuse to run on an event loop
 * thread; call them from a worker thread (a blocking REST method, {@code @Blocking}, or an executor). Host resolution
 * counts towards the deadline: a resolver slower than the time left ends the fetch with
 * {@link FetchException.Kind#TIMEOUT}.
 */
@ApplicationScoped
public class SafeFetcher {

    @Inject
    OutboundConfig config;

    @Inject
    HostResolver resolver;

    @Inject
    VertxFetch fetch;

    /** The most host resolutions running at once; further ones wait in a queue, still bounded by their deadline. */
    static final int RESOLVER_THREADS = 8;

    /** Runs host resolutions, so that a caller stops waiting at its deadline even when the resolver does not. */
    private final ThreadPoolExecutor resolutions = resolverPool();

    private static ThreadPoolExecutor resolverPool() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                RESOLVER_THREADS, RESOLVER_THREADS, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
                    Thread thread = new Thread(task, "quarkpress-outbound-resolver");
                    thread.setDaemon(true);
                    return thread;
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    @PreDestroy
    void stop() {
        resolutions.shutdownNow();
    }

    /** The configured limits: {@code timeout}, {@code max-redirects} and {@code max-response-size}. */
    public FetchLimits defaults() {
        return new FetchLimits(
                config.timeout(),
                config.maxRedirects(),
                config.maxResponseSize().asLongValue());
    }

    /**
     * Whether {@code url} passes the URL test (scheme, host, user info, port, addresses), without a request. Resolves
     * the host within {@code quarkpress.outbound.timeout}.
     *
     * @throws IllegalStateException on an event loop thread
     */
    public boolean allowed(String url) {
        requireBlockingAllowed();
        if (url == null) {
            return false;
        }
        try {
            check(
                    new URI(url),
                    FetchException.Kind.REFUSED_URL,
                    System.nanoTime() + config.timeout().toNanos());
            return true;
        } catch (URISyntaxException | FetchException e) {
            return false;
        }
    }

    /**
     * Fetches {@code url}, following redirects within {@code limits}.
     *
     * @throws FetchException {@link FetchException.Kind#REFUSED_URL} or {@link FetchException.Kind#REFUSED_REDIRECT}
     *     when the URL or a redirect target fails the URL test (before any connection to it), or the kind of the
     *     failure of the exchange
     * @throws IllegalStateException on an event loop thread
     */
    public FetchResponse get(URI url, FetchLimits limits) throws FetchException {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(limits, "limits");
        requireBlockingAllowed();
        long deadline = System.nanoTime() + limits.timeout().toNanos();
        URI current = url;
        FetchException.Kind refusal = FetchException.Kind.REFUSED_URL;
        for (int redirects = 0; ; redirects++) {
            Target target = check(current, refusal, deadline);
            VertxFetch.Answer answer = fetch.get(
                    target.address(),
                    target.host(),
                    target.port(),
                    target.ssl(),
                    target.requestUri(),
                    deadline,
                    limits.timeout(),
                    config.idleTimeout(),
                    limits.maxBytes());
            FetchResponse response = new FetchResponse(
                    answer.status(), answer.reason(), answer.headers(), current, answer.body(), answer.body());
            List<String> location = response.header("Location");
            if (!isRedirect(answer.status()) || location.isEmpty() || redirects >= limits.maxRedirects()) {
                return response;
            }
            answer.body().close();
            current = next(current, location.get(0));
            refusal = FetchException.Kind.REFUSED_REDIRECT;
        }
    }

    private static void requireBlockingAllowed() {
        if (!BlockingOperationControl.isBlockingAllowed()) {
            throw new IllegalStateException("an outbound fetch blocks; it may not run on an event loop thread");
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /** The target of a redirect from {@code from} to {@code location}. */
    private static URI next(URI from, String location) throws FetchException {
        try {
            URI base = from;
            if (base.getRawPath() == null || base.getRawPath().isEmpty()) {
                // URI.resolve against an authority without a path would glue the reference to the host
                base = new URI(from.getScheme() + "://" + from.getRawAuthority() + "/");
            }
            return base.resolve(new URI(location.strip()));
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw FetchException.refused(FetchException.Kind.REFUSED_REDIRECT);
        }
    }

    /** The URL test; resolves the host once, before {@code deadline}, and returns the address to connect to. */
    private Target check(URI url, FetchException.Kind refusal, long deadline) throws FetchException {
        String scheme = url.getScheme();
        boolean ssl;
        if ("http".equalsIgnoreCase(scheme)) {
            ssl = false;
        } else if ("https".equalsIgnoreCase(scheme)) {
            ssl = true;
        } else {
            throw FetchException.refused(refusal);
        }
        String host = url.getHost();
        if (url.isOpaque() || url.getRawUserInfo() != null || host == null || host.isEmpty() || hasForbidden(host)) {
            throw FetchException.refused(refusal);
        }
        int port = url.getPort() == -1 ? (ssl ? 443 : 80) : url.getPort();
        if (!config.allowedPorts().contains(port)) {
            throw FetchException.refused(refusal);
        }
        List<InetAddress> addresses = resolve(host, deadline);
        if (addresses == null || addresses.isEmpty()) {
            throw new FetchException(FetchException.Kind.DNS, host);
        }
        if (!config.allowPrivateAddresses()) {
            for (InetAddress address : addresses) {
                if (!AddressPolicy.isPublic(address)) {
                    throw FetchException.refused(refusal);
                }
            }
        }
        String path = url.getRawPath() == null || url.getRawPath().isEmpty() ? "/" : url.getRawPath();
        String requestUri = url.getRawQuery() == null ? path : path + "?" + url.getRawQuery();
        return new Target(addresses.get(0), host, port, ssl, requestUri);
    }

    /** The addresses of {@code host}, or a failure when the resolver does not answer before {@code deadline}. */
    private List<InetAddress> resolve(String host, long deadline) throws FetchException {
        long left = deadline - System.nanoTime();
        if (left <= 0) {
            throw new FetchException(FetchException.Kind.TIMEOUT, "no time was left to resolve " + host);
        }
        Future<List<InetAddress>> answer;
        try {
            answer = resolutions.submit(() -> resolver.resolve(host));
        } catch (RejectedExecutionException e) {
            throw new FetchException(FetchException.Kind.IO, "the fetcher is stopped", e);
        }
        try {
            return answer.get(left, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            answer.cancel(true);
            throw new FetchException(FetchException.Kind.TIMEOUT, "resolving " + host + " took too long", e);
        } catch (InterruptedException e) {
            answer.cancel(true);
            Thread.currentThread().interrupt();
            throw new FetchException(FetchException.Kind.IO, "interrupted while resolving " + host, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof UnknownHostException) {
                throw new FetchException(
                        FetchException.Kind.DNS, cause.getMessage() == null ? host : cause.getMessage(), cause);
            }
            throw new FetchException(FetchException.Kind.IO, String.valueOf(cause.getMessage()), cause);
        }
    }

    /** Whether the host holds a character that only an IPv6 literal, a port or a URL's later parts would. */
    private static boolean hasForbidden(String host) {
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c == '[' || c == ']' || c == ':' || c == '#' || c == '?') {
                return true;
            }
        }
        return false;
    }

    private record Target(InetAddress address, String host, int port, boolean ssl, String requestUri) {}
}
