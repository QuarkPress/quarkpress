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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WordPressSourcesTest {

    @TempDir
    Path tmp;

    private HttpServer server;
    private final Map<String, byte[]> served = new ConcurrentHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] body = served.get(exchange.getRequestURI().getPath());
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private URI base() {
        return URI.create("http://" + server.getAddress().getHostString() + ":"
                + server.getAddress().getPort() + "/");
    }

    /** Serves every bootstrap file plus {@code extra}, and returns a release pinning the served content. */
    private WordPressRelease serve(Map<String, String> extra) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        for (String path : WordPressSources.BOOTSTRAP_FILES) {
            files.put(path, "<?php // " + path + "\n");
        }
        files.putAll(extra);
        Map<String, String> pins = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            byte[] body = e.getValue().getBytes(StandardCharsets.UTF_8);
            served.put("/9.9.9/src/" + e.getKey(), body);
            pins.put(e.getKey(), sha256(body));
        }
        return new WordPressRelease("9.9.9", "0".repeat(40), pins);
    }

    private static String sha256(byte[] body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    }

    @Test
    void downloadsVerifiesAndIsIdempotent() throws Exception {
        WordPressRelease release = serve(Map.of("wp-includes/blocks.php", "<?php // blocks\n"));
        Path dir = tmp.resolve("wp");

        Path result = WordPressSources.ensure(release, dir, base(), List.of("wp-includes/blocks.php"));

        assertThat(result).isEqualTo(dir);
        assertThat(Files.readString(dir.resolve("wp-includes/blocks.php"))).isEqualTo("<?php // blocks\n");
        assertThat(Files.readString(dir.resolve("wp-includes/plugin.php"))).contains("plugin.php");
        assertThat(dir.resolve(".verified")).exists();
        assertThat(requests.get()).isEqualTo(4);

        WordPressSources.ensure(release, dir, base(), List.of("wp-includes/blocks.php"));
        assertThat(requests.get()).isEqualTo(4);
    }

    @Test
    void hashMismatchDeletesTheFileAndFails() throws Exception {
        WordPressRelease good = serve(Map.of("wp-includes/blocks.php", "<?php // blocks\n"));
        Map<String, String> pins = new LinkedHashMap<>(good.sha256ByPath());
        pins.put("wp-includes/blocks.php", "f".repeat(64));
        WordPressRelease wrong = new WordPressRelease("9.9.9", good.commit(), pins);
        Path dir = tmp.resolve("wp");

        assertThatThrownBy(() -> WordPressSources.ensure(wrong, dir, base(), List.of("wp-includes/blocks.php")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("SHA-256 mismatch for wp-includes/blocks.php");
        assertThat(dir.resolve("wp-includes/blocks.php")).doesNotExist();
    }

    @Test
    void corruptedLocalFileIsDownloadedAgain() throws Exception {
        WordPressRelease release = serve(Map.of("wp-includes/blocks.php", "<?php // blocks\n"));
        Path dir = tmp.resolve("wp");
        Files.createDirectories(dir.resolve("wp-includes"));
        Files.writeString(dir.resolve("wp-includes/blocks.php"), "tampered");

        WordPressSources.ensure(release, dir, base(), List.of("wp-includes/blocks.php"));

        assertThat(Files.readString(dir.resolve("wp-includes/blocks.php"))).isEqualTo("<?php // blocks\n");
    }

    @Test
    void unpinnedFileIsRejected() throws Exception {
        WordPressRelease release = serve(Map.of());
        assertThatThrownBy(() -> WordPressSources.ensure(release, tmp, base(), List.of("wp-includes/nope.php")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wp-includes/nope.php");
    }

    @Test
    void httpErrorFails() throws Exception {
        WordPressRelease release = serve(Map.of("wp-includes/blocks.php", "x"));
        served.remove("/9.9.9/src/wp-includes/blocks.php");
        assertThatThrownBy(() -> WordPressSources.ensure(release, tmp, base(), List.of("wp-includes/blocks.php")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("HTTP 404");
    }

    @Test
    void projectRootComesFromTheProfile() {
        String saved = System.clearProperty("quarkpress.root");
        try {
            assertThatThrownBy(WordPressSources::projectRoot)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("-Pwp-parity");
        } finally {
            if (saved != null) {
                System.setProperty("quarkpress.root", saved);
            }
        }
    }
}
