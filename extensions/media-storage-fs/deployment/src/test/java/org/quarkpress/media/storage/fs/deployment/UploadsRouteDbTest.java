package org.quarkpress.media.storage.fs.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import io.quarkus.test.common.http.TestHTTPResource;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.media.MediaStorage;
import org.quarkpress.media.WriteMode;
import org.quarkpress.media.runtime.ActiveStorage;
import org.quarkpress.media.storage.fs.runtime.FsMediaStorage;

/** The stored files are served under the uploads path, and nothing else is. */
class UploadsRouteDbTest {

    static final Path DIRECTORY = FsApp.directory("uploads-route");

    @RegisterExtension
    static final QuarkusExtensionTest app = FsApp.multisite(DIRECTORY, UploadsRouteDbTest.class);

    @Inject
    ActiveStorage active;

    @TestHTTPResource("/")
    URL root;

    MediaStorage storage;

    @BeforeEach
    void storage() throws IOException {
        storage = active.storage();
        put("2026/11/photo.jpg", "jpeg bytes");
        put("sites/3/2026/11/p.png", "png bytes");
        put("2026/11/data.unknownext", "data");
        put("2026/11/noext", "data");
        put("2026/11/UPPER.JPG", "upper");
        put("2026/11/" + Character.toString(0x424) + " x.jpg", "cyrillic");
        Path secret = DIRECTORY.resolve(".tmp/x.tmp");
        Files.createDirectories(secret.getParent());
        Files.writeString(secret, "temporary");
        Files.writeString(DIRECTORY.getParent().resolve("secret.txt"), "secret");
    }

    @Test
    void theActiveBackendIsTheFilesystem() {
        assertThat(active.name()).isEqualTo("fs");
        assertThat(storage).isInstanceOf(FsMediaStorage.class);
        assertThat(((FsMediaStorage) storage).directory()).isEqualTo(DIRECTORY);
    }

    @Test
    void storedFilesAreServed() throws IOException {
        RawHttp.Answer get = RawHttp.send(root, "GET", "/wp-content/uploads/2026/11/photo.jpg");
        assertThat(get.status()).isEqualTo(200);
        assertThat(get.header("Content-Type")).isEqualTo("image/jpeg");
        assertThat(get.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(get.text()).isEqualTo("jpeg bytes");
        assertThat(get.headers().keySet())
                .doesNotContain("content-security-policy", "content-disposition", "x-frame-options");

        RawHttp.Answer head = RawHttp.send(root, "HEAD", "/wp-content/uploads/2026/11/photo.jpg");
        assertThat(head.status()).isEqualTo(200);
        assertThat(head.header("Content-Type")).isEqualTo("image/jpeg");
        assertThat(head.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(head.body()).isEmpty();

        RawHttp.Answer sub = RawHttp.send(root, "GET", "/site3/wp-content/uploads/sites/3/2026/11/p.png");
        assertThat(sub.status()).isEqualTo(200);
        assertThat(sub.header("Content-Type")).isEqualTo("image/png");
        assertThat(sub.text()).isEqualTo("png bytes");

        RawHttp.Answer deep = RawHttp.send(root, "GET", "/a/b/c/d/wp-content/uploads/2026/11/photo.jpg");
        assertThat(deep.status()).isEqualTo(200);

        RawHttp.Answer unknown = RawHttp.send(root, "GET", "/wp-content/uploads/2026/11/data.unknownext");
        assertThat(unknown.status()).isEqualTo(200);
        assertThat(unknown.header("Content-Type")).isEqualTo("application/octet-stream");
        assertThat(unknown.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(RawHttp.send(root, "GET", "/wp-content/uploads/2026/11/noext")
                        .header("Content-Type"))
                .isEqualTo("application/octet-stream");
        assertThat(RawHttp.send(root, "GET", "/wp-content/uploads/2026/11/UPPER.JPG")
                        .header("Content-Type"))
                .isEqualTo("image/jpeg");

        RawHttp.Answer encoded = RawHttp.send(root, "GET", "/wp-content/uploads/2026/11/%D0%A4%20x.jpg?ver=1");
        assertThat(encoded.status()).isEqualTo(200);
        assertThat(encoded.text()).isEqualTo("cyrillic");
    }

    @Test
    void traversalIsNotServed() throws IOException {
        for (String path : List.of(
                "/wp-content/uploads/../../pom.xml",
                "/wp-content/uploads/../secret.txt",
                "/wp-content/uploads/%2e%2e/secret.txt",
                "/wp-content/uploads/%2E%2E/secret.txt",
                "/wp-content/uploads/2026/..%2f..%2f../secret.txt",
                "/wp-content/uploads/2026",
                "/wp-content/uploads/2026/11",
                "/wp-content/uploads/",
                "/wp-content/uploads/2026/11/missing.jpg",
                "/wp-content/uploads/a%00b",
                "/wp-content/uploads/a%5cb",
                "/wp-content/uploads/2026//11/photo.jpg",
                "/wp-content/uploads/2026/./11/photo.jpg",
                "/wp-content/uploads/.tmp/x.tmp",
                "/wp-content/uploads/%2etmp/x.tmp",
                "/wp-content/uploads/2026/11/%252e%252e")) {
            RawHttp.Answer answer = RawHttp.send(root, "GET", path);
            assertThat(answer.status()).as(path).isEqualTo(404);
            assertThat(answer.text()).as(path).doesNotContain("secret", "temporary", "<project", "jpeg bytes");
        }

        // a malformed escape or one that is not UTF-8: refused by the HTTP server before any route, or not found
        for (String path : List.of("/wp-content/uploads/2026/11/%zz.jpg", "/wp-content/uploads/2026/11/%ff.jpg")) {
            assertThat(RawHttp.send(root, "GET", path).status()).as(path).isIn(400, 404);
        }

        RawHttp.Answer post = RawHttp.send(root, "POST", "/wp-content/uploads/2026/11/photo.jpg");
        assertThat(post.status()).isIn(404, 405);
        RawHttp.Answer put = RawHttp.send(root, "PUT", "/wp-content/uploads/2026/11/new.jpg");
        assertThat(put.status()).isIn(404, 405);
        assertThat(Files.readString(DIRECTORY.resolve("2026/11/photo.jpg"))).isEqualTo("jpeg bytes");
        assertThat(DIRECTORY.resolve("2026/11/new.jpg")).doesNotExist();
    }

    @Test
    void symbolicLinksAreNotServed() throws IOException {
        Path outside = Files.writeString(DIRECTORY.getParent().resolve("outside.txt"), "outside");
        Path outsideFolder = Files.createDirectories(DIRECTORY.getParent().resolve("outside-folder"));
        Files.writeString(outsideFolder.resolve("photo.jpg"), "outside folder");
        link("2026/11/out.jpg", outside);
        link("2026/11/in.jpg", DIRECTORY.resolve("2026/11/photo.jpg"));
        link("2026/out", outsideFolder);
        link("2026/in", DIRECTORY.resolve("2026/11"));
        for (String key : List.of("2026/11/out.jpg", "2026/11/in.jpg", "2026/out/photo.jpg", "2026/in/photo.jpg")) {
            for (String method : List.of("GET", "HEAD")) {
                RawHttp.Answer answer = RawHttp.send(root, method, "/wp-content/uploads/" + key);
                assertThat(answer.status()).as(method + " " + key).isEqualTo(404);
                assertThat(answer.text()).as(method + " " + key).doesNotContain("outside", "jpeg bytes");
            }
        }
        // the file the links name is served under its own key
        assertThat(RawHttp.send(root, "GET", "/wp-content/uploads/2026/11/photo.jpg")
                        .text())
                .isEqualTo("jpeg bytes");
    }

    @Test
    void largeFilesAreSentWhole() throws IOException {
        StringBuilder content = new StringBuilder();
        for (int i = 0; content.length() < 3 << 20; i++) {
            content.append(i).append('\n');
        }
        put("2026/11/large.txt", content.toString());
        RawHttp.Answer whole = RawHttp.send(root, "GET", "/wp-content/uploads/2026/11/large.txt");
        assertThat(whole.status()).isEqualTo(200);
        assertThat(whole.header("Content-Length")).isEqualTo(String.valueOf(content.length()));
        assertSameBytes(whole.body(), content.toString().getBytes(StandardCharsets.UTF_8), "whole file");
        RawHttp.Answer head = RawHttp.send(root, "HEAD", "/wp-content/uploads/2026/11/large.txt");
        assertThat(head.header("Content-Length")).isEqualTo(String.valueOf(content.length()));
        assertThat(head.body()).isEmpty();
        long first = (1 << 20) + 5;
        RawHttp.Answer part =
                RawHttp.send(root, "GET", "/wp-content/uploads/2026/11/large.txt", "Range: bytes=" + first + "-\r\n");
        assertThat(part.status()).isEqualTo(206);
        assertSameBytes(part.body(), content.substring((int) first).getBytes(StandardCharsets.UTF_8), "range");
    }

    @Test
    void chunksArriveInOrderToASlowReader() throws Exception {
        // a client with a tiny receive buffer fills the connection after almost every chunk, so the transfer waits for
        // the connection to drain again and again: each chunk must still be read once, in order
        byte[] content = new byte[8 << 20];
        new java.util.Random(42).nextBytes(content);
        Path source = Files.createDirectories(DIRECTORY.getParent().resolve("sources"))
                .resolve("slow.bin");
        Files.write(source, content);
        if (!storage.exists("2026/11/slow.bin")) {
            storage.put("2026/11/slow.bin", source, "application/octet-stream", WriteMode.CREATE_NEW);
        }
        long first = 3 * 65536 + 17;
        for (int i = 0; i < 4; i++) {
            assertSameBytes(slowGet("/wp-content/uploads/2026/11/slow.bin", ""), content, "whole file, run " + i);
            assertSameBytes(
                    slowGet("/wp-content/uploads/2026/11/slow.bin", "Range: bytes=" + first + "-\r\n"),
                    Arrays.copyOfRange(content, (int) first, content.length),
                    "range, run " + i);
        }
    }

    /** The body of a GET read through a small receive buffer, a little at a time. */
    private byte[] slowGet(String path, String headerLines) throws Exception {
        try (Socket socket = new Socket()) {
            socket.setReceiveBufferSize(4096);
            socket.connect(new InetSocketAddress(root.getHost(), root.getPort()));
            socket.setSoTimeout(60_000);
            socket.getOutputStream()
                    .write((("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n" + headerLines
                                    + "\r\n"))
                            .getBytes(StandardCharsets.ISO_8859_1));
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            int reads = 0;
            for (int n; (n = in.read(buffer)) >= 0; ) {
                all.write(buffer, 0, n);
                if (++reads % 64 == 0) {
                    Thread.sleep(1);
                }
            }
            byte[] raw = all.toByteArray();
            int end = 0;
            while (end + 3 < raw.length
                    && !(raw[end] == '\r' && raw[end + 1] == '\n' && raw[end + 2] == '\r' && raw[end + 3] == '\n')) {
                end++;
            }
            assertThat(end + 3).as("end of the headers").isLessThan(raw.length);
            return Arrays.copyOfRange(raw, end + 4, raw.length);
        }
    }

    /** Compares bodies by length, SHA-256 and the first differing offset, never by printing them. */
    private static void assertSameBytes(byte[] actual, byte[] expected, String what) throws IOException {
        int differs = Arrays.mismatch(actual, expected);
        String digest;
        try {
            java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
            digest = java.util.HexFormat.of().formatHex(sha.digest(actual)) + " vs "
                    + java.util.HexFormat.of().formatHex(sha.digest(expected));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException(impossible);
        }
        assertThat(differs)
                .as(
                        "%s: %d bytes vs %d expected, sha-256 %s, first difference at offset %d",
                        what, actual.length, expected.length, digest, differs)
                .isEqualTo(-1);
    }

    private static void link(String key, Path target) throws IOException {
        Path link = DIRECTORY.resolve(key);
        Files.createDirectories(link.getParent());
        Files.deleteIfExists(link);
        Files.createSymbolicLink(link, target);
    }

    @Test
    void singleRangesAreServed() throws IOException {
        put("2026/11/range.txt", "0123456789");
        String path = "/wp-content/uploads/2026/11/range.txt";
        RawHttp.Answer whole = RawHttp.send(root, "GET", path);
        assertThat(whole.status()).isEqualTo(200);
        assertThat(whole.header("Accept-Ranges")).isEqualTo("bytes");
        assertThat(whole.text()).isEqualTo("0123456789");

        RawHttp.Answer middle = RawHttp.send(root, "GET", path, "Range: bytes=2-4\r\n");
        assertThat(middle.status()).isEqualTo(206);
        assertThat(middle.header("Content-Range")).isEqualTo("bytes 2-4/10");
        assertThat(middle.header("Content-Type")).isEqualTo("text/plain");
        assertThat(middle.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(middle.header("Accept-Ranges")).isEqualTo("bytes");
        assertThat(middle.text()).isEqualTo("234");

        RawHttp.Answer from = RawHttp.send(root, "GET", path, "Range: bytes=7-\r\n");
        assertThat(from.status()).isEqualTo(206);
        assertThat(from.header("Content-Range")).isEqualTo("bytes 7-9/10");
        assertThat(from.text()).isEqualTo("789");

        RawHttp.Answer suffix = RawHttp.send(root, "GET", path, "Range: bytes=-3\r\n");
        assertThat(suffix.status()).isEqualTo(206);
        assertThat(suffix.header("Content-Range")).isEqualTo("bytes 7-9/10");
        assertThat(suffix.text()).isEqualTo("789");

        RawHttp.Answer head = RawHttp.send(root, "HEAD", path, "Range: bytes=2-4\r\n");
        assertThat(head.status()).isEqualTo(206);
        assertThat(head.header("Content-Range")).isEqualTo("bytes 2-4/10");
        assertThat(head.body()).isEmpty();

        RawHttp.Answer beyond = RawHttp.send(root, "GET", path, "Range: bytes=10-\r\n");
        assertThat(beyond.status()).isEqualTo(416);
        assertThat(beyond.header("Content-Range")).isEqualTo("bytes */10");
        assertThat(beyond.text()).isEmpty();

        RawHttp.Answer several = RawHttp.send(root, "GET", path, "Range: bytes=0-1,5-6\r\n");
        assertThat(several.status()).isEqualTo(200);
        assertThat(several.text()).isEqualTo("0123456789");
    }

    @Test
    void anUnchangedFileIsNotSentAgain() throws IOException {
        put("2026/11/cached.txt", "cached");
        String path = "/wp-content/uploads/2026/11/cached.txt";
        // an hour old, so a condition a minute after it is still in the past
        Instant modified = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        Files.setLastModifiedTime(DIRECTORY.resolve("2026/11/cached.txt"), FileTime.from(modified));
        DateTimeFormatter http = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH);
        String lastModified = http.format(modified.atOffset(ZoneOffset.UTC));

        RawHttp.Answer first = RawHttp.send(root, "GET", path);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.header("Last-Modified")).isEqualTo(lastModified);

        RawHttp.Answer same = RawHttp.send(root, "GET", path, "If-Modified-Since: " + lastModified + "\r\n");
        assertThat(same.status()).isEqualTo(304);
        assertThat(same.body()).isEmpty();
        assertThat(same.header("Last-Modified")).isEqualTo(lastModified);

        String later = http.format(modified.plusSeconds(60).atOffset(ZoneOffset.UTC));
        assertThat(RawHttp.send(root, "GET", path, "If-Modified-Since: " + later + "\r\n")
                        .status())
                .isEqualTo(304);

        String future = http.format(Instant.now().plus(1, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC));
        RawHttp.Answer ahead = RawHttp.send(root, "GET", path, "If-Modified-Since: " + future + "\r\n");
        assertThat(ahead.status())
                .as("a date after the server's clock is ignored")
                .isEqualTo(200);
        assertThat(ahead.text()).isEqualTo("cached");

        String earlier = http.format(modified.minusSeconds(1).atOffset(ZoneOffset.UTC));
        RawHttp.Answer changed = RawHttp.send(root, "GET", path, "If-Modified-Since: " + earlier + "\r\n");
        assertThat(changed.status()).isEqualTo(200);
        assertThat(changed.text()).isEqualTo("cached");

        assertThat(RawHttp.send(root, "GET", path, "If-Modified-Since: yesterday\r\n")
                        .status())
                .isEqualTo(200);
    }

    @Test
    void ifRangeAndTheUnitsCase() throws IOException {
        put("2026/11/if-range.txt", "0123456789");
        String path = "/wp-content/uploads/2026/11/if-range.txt";
        Instant modified = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        Files.setLastModifiedTime(DIRECTORY.resolve("2026/11/if-range.txt"), FileTime.from(modified));
        DateTimeFormatter http = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH);
        String current = http.format(modified.atOffset(ZoneOffset.UTC));
        String older = http.format(modified.minusSeconds(1).atOffset(ZoneOffset.UTC));

        RawHttp.Answer same = RawHttp.send(root, "GET", path, "Range: bytes=2-4\r\nIf-Range: " + current + "\r\n");
        assertThat(same.status()).isEqualTo(206);
        assertThat(same.text()).isEqualTo("234");

        for (String ifRange : List.of(older, "\"some-tag\"", "W/\"x\"", "garbage")) {
            RawHttp.Answer whole = RawHttp.send(root, "GET", path, "Range: bytes=2-4\r\nIf-Range: " + ifRange + "\r\n");
            assertThat(whole.status()).as(ifRange).isEqualTo(200);
            assertThat(whole.header("Content-Range")).as(ifRange).isNull();
            assertThat(whole.text()).as(ifRange).isEqualTo("0123456789");
        }

        RawHttp.Answer upper = RawHttp.send(root, "GET", path, "Range: BYTES=2-4\r\n");
        assertThat(upper.status()).isEqualTo(206);
        assertThat(upper.header("Content-Range")).isEqualTo("bytes 2-4/10");
        assertThat(upper.text()).isEqualTo("234");
    }

    @Test
    void dotNamesAreNotServedAndKeysAreDecodedOnce() throws IOException {
        put("2026/11/.hidden", "hidden");
        assertThat(storage.exists("2026/11/.hidden")).isTrue();
        RawHttp.Answer hidden = RawHttp.send(root, "GET", "/wp-content/uploads/2026/11/.hidden");
        assertThat(hidden.status()).isEqualTo(404);
        assertThat(hidden.text()).doesNotContain("hidden");
        assertThat(RawHttp.send(root, "GET", "/wp-content/uploads/2026/11/%2ehidden")
                        .status())
                .isEqualTo(404);

        put("2026/11/%41.jpg", "percent");
        RawHttp.Answer once = RawHttp.send(root, "GET", "/wp-content/uploads/2026/11/%2541.jpg");
        assertThat(once.status()).isEqualTo(200);
        assertThat(once.text()).isEqualTo("percent");
        assertThat(RawHttp.send(root, "GET", "/wp-content/uploads/2026/11/%41.jpg")
                        .status())
                .isEqualTo(404);
    }

    @Test
    void otherPathsAreLeftToTheApplication() throws IOException {
        assertThat(RawHttp.send(root, "GET", "/wp-content/other/2026/11/photo.jpg")
                        .status())
                .isEqualTo(404);
        assertThat(RawHttp.send(root, "GET", "/a/b/c/d/e/wp-content/uploads/2026/11/photo.jpg")
                        .status())
                .isEqualTo(404);
        assertThat(RawHttp.send(root, "GET", "/2026/11/photo.jpg").status()).isEqualTo(404);
    }

    private void put(String key, String content) throws IOException {
        if (storage.exists(key)) {
            return;
        }
        Path source = Files.createDirectories(DIRECTORY.getParent().resolve("sources"))
                .resolve(Integer.toHexString(key.hashCode()));
        Files.writeString(source, content, StandardCharsets.UTF_8);
        storage.put(key, source, "application/octet-stream", WriteMode.CREATE_NEW);
    }
}
