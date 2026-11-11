package org.quarkpress.outbound.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.test.QuarkusExtensionTest;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.outbound.FetchException;
import org.quarkpress.outbound.SafeFetcher;

/** With the default settings, every URL aimed inside is refused before a connection. */
class PrivateAddressesTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            new QuarkusExtensionTest().withApplicationRoot(jar -> jar.addClasses(MappedResolver.class));

    @Inject
    SafeFetcher fetcher;

    @Inject
    MappedResolver resolver;

    @Inject
    Vertx vertx;

    @Test
    void everyInsideAddressIsRefused() {
        resolver.map("inside.test", "10.0.0.1");
        resolver.map("mixed.test", "203.0.113.9", "10.0.0.1");
        resolver.map("half.test", "8.8.8.8", "10.0.0.1");
        resolver.map("0x7f000001", "127.0.0.1");
        resolver.map("public.test", "8.8.8.8");
        resolver.map("public6.test", "2606:4700::1111");
        // names whose IPv6 answers are inside, or carry an inside IPv4 address
        resolver.map("loop6.test", "::1");
        resolver.map("compat.test", "::7f00:1");
        resolver.map("nat64.test", "64:ff9b::a00:1");
        resolver.map("nat64-local.test", "64:ff9b:1::a9fe:a9fe");
        resolver.map("teredo.test", "2001:0:4136:e378:8000:63bf:3fff:fdd2");
        resolver.map("ula.test", "fd00::1");
        resolver.map("mapped.test", mappedIpv6(127, 0, 0, 1));

        // the same settings allow a public host, so the refusals below are the address, port, scheme and user rules
        assertThat(fetcher.allowed("http://public.test/")).isTrue();
        assertThat(fetcher.allowed("https://public.test:8080/x?y#z")).isTrue();
        assertThat(fetcher.allowed("http://public6.test/")).isTrue();

        List<String> refused = List.of(
                "http://127.0.0.1/",
                "http://[::1]/",
                "http://[::ffff:127.0.0.1]/",
                "http://10.1.2.3/",
                "http://169.254.169.254/latest/meta-data/",
                "http://inside.test/",
                "http://mixed.test/",
                "http://half.test/",
                "http://0x7f000001/",
                "http://loop6.test/",
                "http://compat.test/",
                "http://nat64.test/",
                "http://nat64-local.test/",
                "http://teredo.test/",
                "http://ula.test/",
                "http://mapped.test/",
                "http://user@public.test/",
                "ftp://public.test/",
                "http://public.test:22/",
                "public.test/",
                "http:///x");
        for (String url : refused) {
            assertThat(fetcher.allowed(url)).as(url).isFalse();
            assertThatThrownBy(() -> fetcher.get(URI.create(url), fetcher.defaults()))
                    .as(url)
                    .isInstanceOfSatisfying(FetchException.class, e -> {
                        assertThat(e.kind()).isEqualTo(FetchException.Kind.REFUSED_URL);
                        assertThat(e.getMessage()).isEqualTo("A valid URL was not provided.");
                    });
        }

        assertThat(fetcher.allowed("http://nowhere.invalid/")).isFalse();
        assertThatThrownBy(() -> fetcher.get(URI.create("http://nowhere.invalid/"), fetcher.defaults()))
                .isInstanceOfSatisfying(
                        FetchException.class, e -> assertThat(e.kind()).isEqualTo(FetchException.Kind.DNS));
    }

    @Test
    void refusesToBlockAnEventLoop() throws Exception {
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        vertx.runOnContext(ignored -> {
            try {
                fetcher.allowed("http://public.test/");
                failure.complete(null);
            } catch (RuntimeException e) {
                failure.complete(e);
            }
        });
        assertThat(failure.get(10, TimeUnit.SECONDS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("an outbound fetch blocks; it may not run on an event loop thread");
    }

    private static InetAddress mappedIpv6(int a, int b, int c, int d) {
        byte[] bytes = new byte[16];
        bytes[10] = (byte) 0xff;
        bytes[11] = (byte) 0xff;
        bytes[12] = (byte) a;
        bytes[13] = (byte) b;
        bytes[14] = (byte) c;
        bytes[15] = (byte) d;
        try {
            InetAddress address = Inet6Address.getByAddress(null, bytes, -1);
            assertThat(address).isInstanceOf(Inet6Address.class);
            return address;
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
