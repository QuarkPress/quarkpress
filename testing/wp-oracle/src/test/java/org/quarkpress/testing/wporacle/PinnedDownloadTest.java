package org.quarkpress.testing.wporacle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PinnedDownloadTest {

    @TempDir
    Path tmp;

    private HttpServer server;
    private final AtomicReference<byte[]> served = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/release.zip", exchange -> {
            requests.incrementAndGet();
            byte[] body = served.get();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private URI uri() {
        return URI.create("http://" + server.getAddress().getHostString() + ":"
                + server.getAddress().getPort() + "/release.zip");
    }

    private static String sha256(byte[] body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    }

    @Test
    void aPinFileNamesOneFileAndItsUrl() {
        PinnedDownload pin = PinnedDownload.parse(
                "demo",
                "# demo release\n# url https://example.org/files/demo-1.zip\n" + "a".repeat(64) + "  demo-1.zip\n");
        assertThat(pin.uri()).isEqualTo(URI.create("https://example.org/files/demo-1.zip"));
        assertThat(pin.fileName()).isEqualTo("demo-1.zip");
        assertThat(pin.sha256()).isEqualTo("a".repeat(64));
    }

    @Test
    void badPinFilesAreRefused() {
        assertThatThrownBy(() -> PinnedDownload.parse("demo", "a".repeat(64) + "  demo.zip\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("pin file demo lacks a '# url <uri>' line");
        assertThatThrownBy(() -> PinnedDownload.parse(
                        "demo",
                        "# url https://example.org/d.zip\n" + "a".repeat(64) + "  a.zip\n" + "b".repeat(64)
                                + "  b.zip\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("pin file demo must pin exactly one file");
        assertThatThrownBy(() -> PinnedDownload.parse(
                        "demo", "# url https://example.org/d.zip\n" + "a".repeat(64) + "  ../d.zip\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the file name of demo must be a plain file name: ../d.zip");
        assertThatThrownBy(() -> PinnedDownload.parse("demo", "# url https://example.org/d.zip\nnot-a-sha  d.zip\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("pin line must be '<sha256>  <file name>': not-a-sha  d.zip");
    }

    @Test
    void theWordPressReleaseIsPinned() {
        PinnedDownload release = PinnedDownload.load("wordpress-7.1.3-release");
        assertThat(release.uri()).isEqualTo(URI.create("https://downloads.wordpress.org/release/wordpress-7.1.3.zip"));
        assertThat(release.fileName()).isEqualTo("wordpress-7.1.3.zip");
        assertThat(release.sha256()).hasSize(64);
    }

    @Test
    void aVerifiedDownloadIsReused() throws Exception {
        byte[] body = "release bytes".getBytes(StandardCharsets.UTF_8);
        served.set(body);
        PinnedDownload pin = new PinnedDownload("demo", uri(), "demo.zip", sha256(body));
        Path file = WordPressSources.ensure(pin, tmp.resolve("demo"));
        assertThat(file).hasBinaryContent(body);
        assertThat(WordPressSources.ensure(pin, tmp.resolve("demo"))).isEqualTo(file);
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void aWrongChecksumDeletesTheDownload() throws Exception {
        served.set("tampered".getBytes(StandardCharsets.UTF_8));
        PinnedDownload pin = new PinnedDownload("demo", uri(), "demo.zip", "0".repeat(64));
        assertThatThrownBy(() -> WordPressSources.ensure(pin, tmp.resolve("demo")))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith("SHA-256 mismatch for demo.zip: expected " + "0".repeat(64));
        assertThat(Files.exists(tmp.resolve("demo").resolve("demo.zip"))).isFalse();
    }

    @Test
    void namesThatCollideWithBookkeepingFilesOrEscapeAreRefused() {
        String sha = "a".repeat(64);
        URI u = URI.create("https://example.org/d.zip");
        for (String bad : new String[] {".lock", ".verified", "x.part", "a..b", "", "a/b"}) {
            assertThatThrownBy(() -> new PinnedDownload("demo", u, bad, sha))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String bad : new String[] {"../x", "a/b", "a\\b", "", "..", ".hidden"}) {
            assertThatThrownBy(() -> new PinnedDownload(bad, u, "d.zip", sha))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("the name of a pinned download must be a plain name");
        }
    }

    @Test
    void aStalePartFileIsTruncatedAndAMismatchNeverReachesTheTarget() throws Exception {
        byte[] body = "short".getBytes(StandardCharsets.UTF_8);
        served.set(body);
        Path dir = tmp.resolve("demo");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("demo.zip.part"), "stale and much longer than the body");
        PinnedDownload pin = new PinnedDownload("demo", uri(), "demo.zip", sha256(body));
        assertThat(WordPressSources.ensure(pin, dir)).hasBinaryContent(body);

        Path other = tmp.resolve("other");
        Files.createDirectories(other);
        Files.writeString(other.resolve("demo.zip"), "previously verified");
        PinnedDownload wrong = new PinnedDownload("demo", uri(), "demo.zip", "0".repeat(64));
        assertThatThrownBy(() -> WordPressSources.ensure(wrong, other)).isInstanceOf(IOException.class);
        assertThat(Files.exists(other.resolve("demo.zip.part"))).isFalse();
    }

    @Test
    void threadsOfOneJvmWaitForTheLock() throws Exception {
        byte[] body = "release bytes".getBytes(StandardCharsets.UTF_8);
        served.set(body);
        PinnedDownload pin = new PinnedDownload("demo", uri(), "demo.zip", sha256(body));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            java.util.List<java.util.concurrent.Future<Path>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(pool.submit(() -> WordPressSources.ensure(pin, tmp.resolve("demo"))));
            }
            for (java.util.concurrent.Future<Path> result : results) {
                assertThat(result.get()).hasBinaryContent(body);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(requests.get()).isEqualTo(1);
    }
}
