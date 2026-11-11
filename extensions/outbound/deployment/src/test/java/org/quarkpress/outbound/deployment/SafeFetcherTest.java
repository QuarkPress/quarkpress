package org.quarkpress.outbound.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.outbound.FetchException;
import org.quarkpress.outbound.FetchLimits;
import org.quarkpress.outbound.FetchResponse;
import org.quarkpress.outbound.SafeFetcher;

/** The fetcher against local servers, with the address rule lifted so that loopback may be reached. */
class SafeFetcherTest {

    static final LocalServers SERVERS = LocalServers.get();

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(MappedResolver.class, LocalServers.class))
            .overrideRuntimeConfigKey("quarkpress.outbound.allow-private-addresses", "true")
            .overrideRuntimeConfigKey("quarkpress.outbound.allowed-ports", SERVERS.allowedPorts())
            .overrideRuntimeConfigKey("quarkpress.outbound.tls-configuration-name", "outbound-test")
            .overrideRuntimeConfigKey(
                    "quarkus.tls.outbound-test.trust-store.p12.path",
                    Path.of("src/test/resources/pinned-test.p12")
                            .toAbsolutePath()
                            .toString())
            .overrideRuntimeConfigKey("quarkus.tls.outbound-test.trust-store.p12.password", "changeit");

    @Inject
    SafeFetcher fetcher;

    @Inject
    MappedResolver resolver;

    @Test
    void getsABody() throws IOException {
        resolver.map("local.test", "127.0.0.1");
        URI url = URI.create("http://local.test:" + SERVERS.httpPort() + "/ok");
        try (FetchResponse response = fetcher.get(url, fetcher.defaults())) {
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.reason()).isEqualTo("OK");
            assertThat(response.header("x-TEST")).containsExactly("a", "b");
            assertThat(response.header("X-Missing")).isEmpty();
            assertThat(response.url()).isEqualTo(url);
            assertThat(new String(response.body().readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("hello local");
        }
    }

    @Test
    void pinnedConnectionKeepsHostAndSni() throws IOException {
        resolver.map("pinned.test", "127.0.0.1");
        resolver.map("other.test", "127.0.0.1");
        SERVERS.tlsRequests().clear();
        int port = SERVERS.pinnedPort();
        try (FetchResponse response =
                fetcher.get(URI.create("https://pinned.test:" + port + "/"), fetcher.defaults())) {
            assertThat(response.status()).isEqualTo(200);
            assertThat(new String(response.body().readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("tls pinned");
        }
        assertThat(SERVERS.tlsRequests()).containsExactly("pinned sni=pinned.test host=pinned.test:" + port);

        // the trusted certificate of pinned.test does not stand for other.test
        assertThatThrownBy(() -> fetcher.get(URI.create("https://other.test:" + port + "/"), fetcher.defaults()))
                .isInstanceOfSatisfying(
                        FetchException.class, e -> assertThat(e.kind()).isEqualTo(FetchException.Kind.TLS));
        // a certificate for other.test, not trusted, does not stand for pinned.test
        int otherPort = SERVERS.otherPort();
        assertThatThrownBy(() -> fetcher.get(URI.create("https://pinned.test:" + otherPort + "/"), fetcher.defaults()))
                .isInstanceOfSatisfying(
                        FetchException.class, e -> assertThat(e.kind()).isEqualTo(FetchException.Kind.TLS));
    }

    @Test
    void aRebindingHostIsReachedAtTheCheckedAddress() throws IOException {
        // nothing listens on 127.0.0.2: a second resolution would fail to connect
        resolver.rebind("rebind.test", "127.0.0.1", "127.0.0.2");
        SERVERS.httpRequests().clear();
        URI url = URI.create("http://rebind.test:" + SERVERS.httpPort() + "/rebind");
        try (FetchResponse response = fetcher.get(url, fetcher.defaults())) {
            assertThat(response.status()).isEqualTo(200);
        }
        assertThat(resolver.calls("rebind.test")).isEqualTo(1);
        assertThat(SERVERS.httpRequests()).containsExactly("/rebind");
    }

    @Test
    void aRedirectInsideIsRefused() {
        resolver.map("hop.test", "127.0.0.1");
        URI toSsh = URI.create("http://hop.test:" + SERVERS.httpPort() + "/to-ssh");
        assertThatThrownBy(() -> fetcher.get(toSsh, fetcher.defaults()))
                .isInstanceOfSatisfying(FetchException.class, e -> {
                    assertThat(e.kind()).isEqualTo(FetchException.Kind.REFUSED_REDIRECT);
                    assertThat(e.getMessage()).isEqualTo("A valid URL was not provided.");
                });
        // a port outside the allowed list is refused before any connection to it
        URI toSentinel = URI.create("http://hop.test:" + SERVERS.httpPort() + "/to-sentinel");
        assertThatThrownBy(() -> fetcher.get(toSentinel, fetcher.defaults()))
                .isInstanceOfSatisfying(
                        FetchException.class,
                        e -> assertThat(e.kind()).isEqualTo(FetchException.Kind.REFUSED_REDIRECT));
        assertThat(SERVERS.sentinelConnections()).isZero();
    }

    @Test
    void theSixthRedirectIsAnsweredAsIs() throws IOException {
        resolver.map("chain.test", "127.0.0.1");
        URI url = URI.create("http://chain.test:" + SERVERS.httpPort() + "/chain/1");
        try (FetchResponse response = fetcher.get(url, fetcher.defaults())) {
            assertThat(response.status()).isEqualTo(302);
            assertThat(response.header("X-Hop")).containsExactly("6");
            assertThat(response.url()).isEqualTo(URI.create("http://chain.test:" + SERVERS.httpPort() + "/chain/6"));
        }
    }

    @Test
    void abortsAtTheByteCap() throws Exception {
        resolver.map("big.test", "127.0.0.1");
        URI url = URI.create("http://big.test:" + SERVERS.bigPort() + "/big");
        FetchLimits limits = new FetchLimits(Duration.ofSeconds(20), 5, LocalServers.MIB);
        try (FetchResponse response = fetcher.get(url, limits)) {
            InputStream body = response.body();
            assertThatThrownBy(body::readAllBytes)
                    .isInstanceOfSatisfying(
                            FetchException.class, e -> assertThat(e.kind()).isEqualTo(FetchException.Kind.TOO_LARGE));
        }
        assertThat(SERVERS.awaitBigDone(10))
                .as("the server saw the connection end")
                .isTrue();
        assertThat(SERVERS.bigWritten()).isLessThan(2 * LocalServers.MIB);
    }

    @Test
    void aTrickleServerTimesOut() {
        resolver.map("slow.test", "127.0.0.1");
        URI url = URI.create("http://slow.test:" + SERVERS.httpPort() + "/trickle");
        FetchLimits limits = new FetchLimits(Duration.ofSeconds(2), 5, LocalServers.MIB);
        long start = System.nanoTime();
        assertThatThrownBy(() -> {
                    try (FetchResponse response = fetcher.get(url, limits)) {
                        response.body().readAllBytes();
                    }
                })
                .isInstanceOfSatisfying(
                        FetchException.class, e -> assertThat(e.kind()).isEqualTo(FetchException.Kind.TIMEOUT));
        long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertThat(elapsed).isBetween(2000L, 3000L);
    }

    @Test
    void aGzipBombCountsDecompressedBytes() throws IOException {
        resolver.map("bomb.test", "127.0.0.1");
        URI url = URI.create("http://bomb.test:" + SERVERS.httpPort() + "/bomb");
        FetchLimits limits = new FetchLimits(Duration.ofSeconds(20), 5, LocalServers.MIB);
        try (FetchResponse response = fetcher.get(url, limits)) {
            assertThatThrownBy(response.body()::readAllBytes)
                    .isInstanceOfSatisfying(
                            FetchException.class, e -> assertThat(e.kind()).isEqualTo(FetchException.Kind.TOO_LARGE));
        }
    }

    @Test
    void aSlowResolverCountsTowardsTheDeadline() {
        resolver.slow("slow-dns.test", 5000, "127.0.0.1");
        URI url = URI.create("http://slow-dns.test:" + SERVERS.httpPort() + "/ok");
        FetchLimits limits = new FetchLimits(Duration.ofSeconds(1), 5, LocalServers.MIB);
        long start = System.nanoTime();
        assertThatThrownBy(() -> fetcher.get(url, limits))
                .isInstanceOfSatisfying(
                        FetchException.class, e -> assertThat(e.kind()).isEqualTo(FetchException.Kind.TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isBetween(1000L, 2000L);
    }

    @Test
    void aSilentServerTimesOutAfterTheIdleTime() {
        // one byte, then silence: the idle timeout (5 s) ends the read long before the exchange's 20 s
        resolver.map("stall.test", "127.0.0.1");
        URI url = URI.create("http://stall.test:" + SERVERS.httpPort() + "/stall");
        FetchLimits limits = new FetchLimits(Duration.ofSeconds(20), 5, LocalServers.MIB);
        long start = System.nanoTime();
        assertThatThrownBy(() -> {
                    try (FetchResponse response = fetcher.get(url, limits)) {
                        response.body().readAllBytes();
                    }
                })
                .isInstanceOfSatisfying(
                        FetchException.class, e -> assertThat(e.kind()).isEqualTo(FetchException.Kind.TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isBetween(5000L, 6500L);
    }

    @Test
    void aSlowReaderIsNotIdle() throws Exception {
        // the reader pauses longer than the idle timeout while the paused response holds the rest of the body
        resolver.map("reader.test", "127.0.0.1");
        URI url = URI.create("http://reader.test:" + SERVERS.httpPort() + "/eight-mib");
        FetchLimits limits = new FetchLimits(Duration.ofSeconds(30), 5, 16 * LocalServers.MIB);
        try (FetchResponse response = fetcher.get(url, limits)) {
            InputStream body = response.body();
            assertThat(body.read()).isZero();
            Thread.sleep(6000);
            assertThat(body.readAllBytes()).hasSize((int) (8 * LocalServers.MIB - 1));
        }
    }

    @Test
    void anIpv6AddressIsReached() throws IOException {
        assumeTrue(SERVERS.ipv6Port() != -1, "no IPv6 loopback on this host");
        resolver.map("v6.test", "::1");
        SERVERS.httpRequests().clear();
        URI url = URI.create("http://v6.test:" + SERVERS.ipv6Port() + "/ok");
        try (FetchResponse response = fetcher.get(url, fetcher.defaults())) {
            assertThat(response.status()).isEqualTo(200);
        }
        assertThat(SERVERS.httpRequests()).containsExactly("[::1]/ok");
    }
}
