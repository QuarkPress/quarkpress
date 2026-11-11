package org.quarkpress.outbound.runtime;

import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.SocketAddress;
import io.vertx.core.net.TrustOptions;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;
import org.quarkpress.outbound.FetchException;

/**
 * One GET exchange over Vert.x core's HTTP client, connected to an address the caller checked: the connection goes
 * to that address while the {@code Host} header, TLS SNI and certificate verification use the URL's host. Redirects
 * are not followed here.
 */
@ApplicationScoped
public class VertxFetch {

    /** The status line, the headers and the body of one exchange. */
    public record Answer(int status, String reason, Map<String, List<String>> headers, BodyStream body) {}

    @Inject
    Vertx vertx;

    @Inject
    OutboundConfig config;

    @Inject
    TlsConfigurationRegistry tls;

    private HttpClient client;

    @PostConstruct
    void start() {
        HttpClientOptions options = new HttpClientOptions()
                .setForceSni(true)
                .setVerifyHost(true)
                .setDecompressionSupported(true)
                .setConnectTimeout(millis(config.connectTimeout()));
        trustOptions(tls, config.tlsConfigurationName()).ifPresent(options::setTrustOptions);
        client = vertx.createHttpClient(options);
    }

    /**
     * The trust store of the named TLS registry configuration, empty when none is named.
     *
     * @throws IllegalStateException when the name is unknown or its configuration has no trust store
     */
    static Optional<TrustOptions> trustOptions(TlsConfigurationRegistry registry, Optional<String> name) {
        if (name.isEmpty()) {
            return Optional.empty();
        }
        TlsConfiguration configuration = registry.get(name.get())
                .orElseThrow(() -> new IllegalStateException(
                        "quarkpress.outbound.tls-configuration-name names no TLS configuration: " + name.get()));
        TrustOptions trust = configuration.getTrustStoreOptions();
        if (trust == null) {
            throw new IllegalStateException("the TLS configuration " + name.get() + " has no trust store");
        }
        return Optional.of(trust);
    }

    @PreDestroy
    void stop() {
        if (client != null) {
            client.close();
        }
    }

    /**
     * Sends a GET for {@code requestUri} to {@code address} and waits for the status line and headers.
     *
     * @param host the URL's host: the {@code Host} header, the TLS SNI name and the name the certificate must carry
     * @param deadline the {@link System#nanoTime()} by which the whole exchange, body included, ends
     * @param timeout the exchange's time limit, for messages
     * @param idleTimeout the longest silence before the headers and, while the reader waits, between two reads
     */
    public Answer get(
            InetAddress address,
            String host,
            int port,
            boolean ssl,
            String requestUri,
            long deadline,
            Duration timeout,
            Duration idleTimeout,
            long maxBytes)
            throws FetchException {
        RequestOptions options = new RequestOptions()
                .setMethod(HttpMethod.GET)
                .setServer(SocketAddress.inetSocketAddress(new InetSocketAddress(address, port)))
                .setHost(host)
                .setPort(port)
                .setSsl(ssl)
                .setURI(requestUri)
                .setFollowRedirects(false)
                .setConnectTimeout(config.connectTimeout().toMillis())
                // Vert.x cancels this timer when the headers arrive; the body stream keeps its own
                .setIdleTimeout(idleTimeout.toMillis())
                .putHeader(HttpHeaders.USER_AGENT, config.userAgent());
        Pending pending = new Pending();
        Future<Answer> answer = client.request(options).compose(request -> {
            pending.started(request);
            return request.send().map(response -> {
                Context context = vertx.getOrCreateContext();
                BodyStream body = new BodyStream(request, response, context, maxBytes, deadline, timeout, idleTimeout);
                if (pending.isAbandoned()) {
                    body.close();
                }
                Map<String, List<String>> headers = new LinkedHashMap<>();
                for (String name : response.headers().names()) {
                    headers.put(name, response.headers().getAll(name));
                }
                return new Answer(response.statusCode(), response.statusMessage(), headers, body);
            });
        });
        long left = deadline - System.nanoTime();
        try {
            if (left <= 0) {
                throw new TimeoutException();
            }
            return answer.toCompletionStage().toCompletableFuture().get(left, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            pending.abandon();
            throw new FetchException(
                    FetchException.Kind.TIMEOUT, "the exchange took longer than " + timeout.toMillis() + " ms", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.abandon();
            throw new FetchException(FetchException.Kind.IO, "interrupted while waiting for the response", e);
        } catch (ExecutionException e) {
            throw failure(e.getCause(), timeout);
        }
    }

    /** The fetch failure for a failure of the Vert.x exchange. */
    static FetchException failure(Throwable cause, Duration timeout) {
        if (cause instanceof FetchException fetch) {
            return fetch;
        }
        String message = cause == null || cause.getMessage() == null ? String.valueOf(cause) : cause.getMessage();
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof TimeoutException || t.getClass().getSimpleName().endsWith("TimeoutException")) {
                return new FetchException(FetchException.Kind.TIMEOUT, message, cause);
            }
        }
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof SSLException || t instanceof CertificateException) {
                return new FetchException(FetchException.Kind.TLS, message, cause);
            }
        }
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof NoRouteToHostException) {
                return new FetchException(FetchException.Kind.CONNECT, message, cause);
            }
        }
        return new FetchException(FetchException.Kind.IO, message, cause);
    }

    private static int millis(Duration duration) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, duration.toMillis()));
    }

    /** The request of an exchange, reset when the caller stops waiting before the answer arrived. */
    private static final class Pending {

        private HttpClientRequest request;
        private boolean abandoned;

        synchronized void started(HttpClientRequest request) {
            this.request = request;
            if (abandoned) {
                request.reset();
            }
        }

        synchronized boolean isAbandoned() {
            return abandoned;
        }

        synchronized void abandon() {
            abandoned = true;
            if (request != null) {
                request.reset();
            }
        }
    }
}
