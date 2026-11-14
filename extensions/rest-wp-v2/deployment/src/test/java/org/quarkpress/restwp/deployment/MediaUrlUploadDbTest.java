package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.NetworkOptions;
import org.quarkpress.testing.wprestparity.MediaFixtures;
import org.quarkpress.testing.wprestparity.MediaFixtures.JpegMeta;

/**
 * Uploads from a URL ({@code POST /wp/v2/media {"url": …}}) against a local image server on the loopback address,
 * which the outbound module is told to reach: the attachment is named after the URL's last path segment as it stands
 * (not decoded), titled by the stored name's stem, its type corrected from the content, answered 201 with
 * {@code Location} and no {@code X-WP-Upload-Attachment-ID}; the network's per-file limit is checked before the
 * upload cap (Part G fact v16), the download is aborted at the cap, a status other than 200 is {@code http_404} with
 * the reason phrase and the first kilobyte of the body, a redirect to a refused port is a refused fetch, a
 * {@code Content-MD5} that does not match is {@code md5_mismatch}, an embedded caption becomes the description, and
 * the request's own title, caption, description and alternative text are ignored. The site's uploader is the author
 * carol.
 */
class MediaUrlUploadDbTest {

    static final Images IMAGES = Images.get();

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(MediaUrlUploadDbTest.class, Images.class)
            .overrideConfigKey("quarkpress.outbound.allow-private-addresses", "true")
            .overrideConfigKey("quarkpress.outbound.allowed-ports", "80,443,8080," + IMAGES.port())
            .overrideConfigKey("quarkpress.media.max-upload-size", "1M");

    private static final String MEDIA = "/wp-json/wp/v2/media";

    private static final ObjectMapper JSON = new ObjectMapper();

    static AgroalDataSource data;
    static SiteId site;
    static String host;
    static UserId carol;

    @Inject
    NetworkOptions network;

    @BeforeAll
    static void fixtures() {
        data = Jdbc.dataSource();
        site = TestData.siteIn(data, "UTC");
        host = Jdbc.strings(data, "select domain from qp_site where id = ?", site.value())
                .get(0);
        carol = TestData.profile(data, "author");
        TestData.member(data, site, carol, "[\"author\"]");
    }

    private static Response fromUrl(String url, Map<String, String> more) {
        StringBuilder body = new StringBuilder("{\"url\":\"").append(url).append('"');
        more.forEach((name, value) ->
                body.append(",\"").append(name).append("\":\"").append(value).append('"'));
        return Http.at(host, "test-" + carol.value())
                .contentType("application/json")
                .body(body.append('}').toString())
                .post(MEDIA);
    }

    private static Response fromUrl(String url) {
        return fromUrl(url, Map.of());
    }

    private static JsonNode json(Response response) {
        try {
            return JSON.readTree(response.asString());
        } catch (IOException failure) {
            throw new AssertionError(response.asString(), failure);
        }
    }

    private static JsonNode created(Response response) {
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(201);
        assertThat(response.header("X-WP-Upload-Attachment-ID")).isNull();
        JsonNode body = json(response);
        assertThat(response.header("Location"))
                .endsWith(MEDIA + "/" + body.get("id").asText());
        return body;
    }

    private static void refused(Response response, int status, String code, String message) {
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(status);
        JsonNode body = json(response);
        assertThat(body.get("code").asText()).as(response.asString()).isEqualTo(code);
        assertThat(body.get("message").asText()).isEqualTo(message);
        assertThat(body.get("data").toString()).isEqualTo("{\"status\":" + status + "}");
    }

    @Test
    void aPublicImageIsUploaded() {
        JsonNode body = created(fromUrl(
                IMAGES.url("/dir/photo.jpg"),
                Map.of("title", "Given", "caption", "Given caption", "description", "Given desc", "alt_text", "Alt")));
        assertThat(body.at("/title/raw").asText()).isEqualTo("photo");
        assertThat(body.at("/mime_type").asText()).isEqualTo("image/jpeg");
        assertThat(body.at("/media_details/file").asText()).matches("\\d{4}/\\d{2}/photo\\.jpg");
        assertThat(body.at("/media_details/width").asInt()).isEqualTo(64);
        // the request's own fields are ignored, as WordPress ignores them for a sideload
        assertThat(body.at("/caption/raw").asText()).isEmpty();
        assertThat(body.at("/description/raw").asText()).isEmpty();
        assertThat(body.at("/alt_text").asText()).isEmpty();
        // a second one is stored under a unique name, and titled after it
        JsonNode second = created(fromUrl(IMAGES.url("/dir/photo.jpg")));
        assertThat(second.at("/title/raw").asText()).isEqualTo("photo-1");
        assertThat(second.at("/media_details/file").asText()).endsWith("/photo-1.jpg");
    }

    @Test
    void theQueryAndFragmentAreNotTheName() {
        JsonNode body = created(fromUrl(IMAGES.url("/dir/query.jpg?size=large#top")));
        assertThat(body.at("/title/raw").asText()).isEqualTo("query");
        assertThat(body.at("/media_details/file").asText()).matches("\\d{4}/\\d{2}/query\\.jpg");
    }

    @Test
    void theNameIsNotDecoded() {
        JsonNode body = created(fromUrl(IMAGES.url("/dir/my%20photo.jpg")));
        assertThat(body.at("/title/raw").asText()).isEqualTo("my20photo");
        assertThat(body.at("/media_details/file").asText()).endsWith("/my20photo.jpg");
        assertThat(IMAGES.log()).contains("GET /dir/my%20photo.jpg 200");
    }

    /** Runs {@code body} with the network's upload checks on and {@code fileupload_maxk} set, then restores both. */
    private void withNetworkLimits(String maxK, Runnable body) {
        List<String> names = List.of("upload_space_check_disabled", "fileupload_maxk");
        Map<String, Optional<JsonNode>> stored = new LinkedHashMap<>();
        names.forEach(name -> stored.put(name, network.find(name)));
        network.set("upload_space_check_disabled", JSON.getNodeFactory().textNode("0"));
        try {
            network.set("fileupload_maxk", JSON.getNodeFactory().textNode(maxK));
            body.run();
        } finally {
            stored.forEach((name, value) -> {
                if (value.isPresent()) {
                    network.set(name, value.get());
                } else {
                    network.delete(name);
                }
            });
        }
    }

    @Test
    void theNetworkFileSizeLimit() {
        withNetworkLimits(
                "1",
                () -> refused(
                        fromUrl(IMAGES.url("/dir/padded.jpg")),
                        400,
                        "rest_upload_file_too_big",
                        "This file is too big. Files must be less than 1 KB in size."));
        // the network's limit answers before the upload cap, with the network's own message (no separator)
        withNetworkLimits(
                "1000",
                () -> refused(
                        fromUrl(IMAGES.url("/dir/large.jpg")),
                        400,
                        "rest_upload_file_too_big",
                        "This file is too big. Files must be less than 1000 KB in size."));
    }

    @Test
    void withoutACapTheNetworkRefusesTheCutBody() {
        // fileupload_maxk 0 leaves no positive cap, so the download is bounded by the outbound module's default size
        // and cut there; the network's limit still answers, as it answers WordPress's unbounded download
        withNetworkLimits(
                "0",
                () -> refused(
                        fromUrl(IMAGES.url("/huge-uncapped.jpg")),
                        400,
                        "rest_upload_file_too_big",
                        "This file is too big. Files must be less than 0 KB in size."));
        long written = IMAGES.waitForReset("/huge-uncapped.jpg");
        assertThat(written).as("bytes the server wrote before the reset").isLessThan(8L * 1024 * 1024);
    }

    @Test
    void anUpperCaseExtensionIsAnImage() {
        JsonNode body = created(fromUrl(IMAGES.url("/dir/UPPER.JPG")));
        assertThat(body.at("/title/raw").asText()).isEqualTo("UPPER");
        assertThat(body.at("/mime_type").asText()).isEqualTo("image/jpeg");
        assertThat(body.at("/media_details/file").asText()).endsWithIgnoringCase("/upper.jpg");
    }

    @Test
    void aFollowedRedirectIsStored() {
        // the file is named after the URL asked for, not the redirect's target
        JsonNode body = created(fromUrl(IMAGES.url("/moved.jpg")));
        assertThat(body.at("/title/raw").asText()).isEqualTo("moved");
        assertThat(body.at("/media_details/file").asText()).endsWith("/moved.jpg");
        assertThat(IMAGES.log()).contains("GET /moved.jpg 301", "GET /dir/photo.jpg 200");
    }

    @Test
    void aPngUnderJpgIsCorrected() {
        JsonNode body = created(fromUrl(IMAGES.url("/dir/pic.jpg")));
        assertThat(body.at("/mime_type").asText()).isEqualTo("image/png");
        assertThat(body.at("/media_details/file").asText()).endsWith("/pic.png");
        assertThat(body.at("/title/raw").asText()).isEqualTo("pic");
    }

    @Test
    void aMissingFileIs500Http404() {
        Response response = fromUrl(IMAGES.url("/nope.jpg"));
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(500);
        assertThat(response.asString())
                .isEqualTo(
                        "{\"code\":\"http_404\",\"message\":\"Not Found\",\"data\":{\"code\":404,\"body\":\"nope\"}}");
        // any status but 200, the body cut at a kilobyte
        Response created = fromUrl(IMAGES.url("/created.jpg"));
        assertThat(created.statusCode()).as(created.asString()).isEqualTo(500);
        JsonNode body = json(created);
        assertThat(body.get("code").asText()).isEqualTo("http_404");
        assertThat(body.get("message").asText()).isEqualTo("Created");
        assertThat(body.at("/data/code").asInt()).isEqualTo(201);
        assertThat(body.at("/data/body").asText()).hasSize(1024).startsWith("xxxx");
    }

    @Test
    void theCapAborts() {
        refused(
                fromUrl(IMAGES.url("/huge.jpg")),
                400,
                "rest_upload_file_too_big",
                "This file is too big. Files must be less than 1,024 KB in size.");
        long written = IMAGES.waitForReset("/huge.jpg");
        assertThat(written).as("bytes the server wrote before the reset").isLessThan(8L * 1024 * 1024);
    }

    @Test
    void aRedirectToARefusedPortIsRefused() {
        Response response = fromUrl(IMAGES.url("/redirect.jpg"));
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(500);
        assertThat(response.asString())
                .isEqualTo("{\"code\":\"http_request_failed\",\"message\":\"A valid URL was not provided.\","
                        + "\"data\":null}");
        // only the redirect itself was fetched
        assertThat(IMAGES.log()).contains("GET /redirect.jpg 302");
    }

    @Test
    void aContentMd5ThatDoesNotMatchIsRefused() {
        Response response = fromUrl(IMAGES.url("/md5.jpg"));
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(500);
        JsonNode body = json(response);
        assertThat(body.get("code").asText()).isEqualTo("md5_mismatch");
        assertThat(body.get("message").asText())
                .startsWith("The checksum of the file (")
                .endsWith(") does not match the expected checksum value (00000000000000000000000000000000).");
        // WordPress's error from its HTTP API carries no data
        assertThat(body.get("data").isNull()).as(response.asString()).isTrue();
        // one that matches passes, as hexadecimal or as base64
        created(fromUrl(IMAGES.url("/md5-ok.jpg")));
        created(fromUrl(IMAGES.url("/md5-b64.jpg")));
        // a header that is neither 32 hexadecimal nor 24 base64 characters is ignored, as WordPress ignores it
        created(fromUrl(IMAGES.url("/md5-bad.jpg")));
        created(fromUrl(IMAGES.url("/md5-bad24.jpg")));
    }

    @Test
    void anEmbeddedCaptionIsTheDescription() {
        JsonNode body = created(fromUrl(IMAGES.url("/captioned.jpg")));
        assertThat(body.at("/description/raw").asText()).isEqualTo("Cap");
        assertThat(body.at("/caption/raw").asText()).isEmpty();
        // a short embedded caption also stands in for the missing embedded title, as WordPress reads it
        assertThat(body.at("/title/raw").asText()).isEqualTo("Cap");
    }

    @Test
    void theParentIsKept() {
        long post = TestData.post(data, site, carol, "post", "draft").value();
        JsonNode body = created(fromUrl(IMAGES.url("/dir/child.jpg"), Map.of("post", Long.toString(post))));
        assertThat(body.get("post").asLong()).isEqualTo(post);
        // the request's other post fields are ignored
        JsonNode plain = created(fromUrl(IMAGES.url("/dir/fields.jpg"), Map.of("status", "private", "slug", "given")));
        assertThat(plain.get("status").asText()).isEqualTo("inherit");
        assertThat(plain.get("slug").asText()).isEqualTo("fields");
    }

    /**
     * The image server on {@code 127.0.0.1}: files by path (some with a {@code Content-MD5}), a 404 page, a 201, a
     * redirect to a refused port and one to a file of its own, a huge body (whose reset it records), and a
     * {@code /log} of every request as {@code METHOD path status}. It starts
     * once per JVM; only its port is shared, as a system property, because the test class is loaded a second time in
     * the application's class loader.
     */
    static final class Images {

        private static final String KEY = Images.class.getName() + ".port";

        private final int port;

        private Images(int port) {
            this.port = port;
        }

        static synchronized Images get() {
            String port = System.getProperty(KEY);
            if (port == null) {
                port = Integer.toString(start());
                System.setProperty(KEY, port);
            }
            return new Images(Integer.parseInt(port));
        }

        int port() {
            return port;
        }

        String url(String pathAndQuery) {
            return "http://127.0.0.1:" + port + pathAndQuery;
        }

        /** Every request seen, as {@code METHOD path status}, oldest first; the huge body's line adds its bytes. */
        List<String> log() {
            try {
                HttpURLConnection connection =
                        (HttpURLConnection) URI.create(url("/log")).toURL().openConnection();
                try (InputStream in = connection.getInputStream()) {
                    String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    return body.isEmpty() ? List.of() : List.of(body.split("\n"));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /** The bytes written to {@code path} before the client reset the connection, waiting for the reset. */
        long waitForReset(String path) {
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (System.nanoTime() < deadline) {
                for (String line : log()) {
                    if (line.startsWith("RESET " + path + " ")) {
                        return Long.parseLong(line.substring(line.lastIndexOf(' ') + 1));
                    }
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            throw new AssertionError("the server saw no reset on " + path + ": " + log());
        }

        private static int start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
                Queue<String> log = new ConcurrentLinkedQueue<>();
                server.createContext("/", exchange -> handle(exchange, log));
                server.setExecutor(Executors.newCachedThreadPool(runnable -> {
                    Thread thread = new Thread(runnable, "images-server");
                    thread.setDaemon(true);
                    return thread;
                }));
                server.start();
                return server.getAddress().getPort();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static void handle(HttpExchange exchange, Queue<String> log) throws IOException {
            String path = exchange.getRequestURI().getRawPath();
            if (path.equals("/log")) {
                byte[] body = String.join("\n", log).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
                return;
            }
            switch (path) {
                case "/dir/photo.jpg",
                        "/dir/query.jpg",
                        "/dir/child.jpg",
                        "/dir/fields.jpg",
                        "/dir/my%20photo.jpg",
                        "/dir/UPPER.JPG" -> file(exchange, log, 200, "image/jpeg", MediaFixtures.jpeg(64, 48));
                case "/md5-ok.jpg" -> {
                    byte[] jpeg = MediaFixtures.jpeg(64, 48);
                    exchange.getResponseHeaders()
                            .add("Content-MD5", HexFormat.of().formatHex(md5(jpeg)));
                    file(exchange, log, 200, "image/jpeg", jpeg);
                }
                case "/md5-b64.jpg" -> {
                    byte[] jpeg = MediaFixtures.jpeg(64, 48);
                    exchange.getResponseHeaders()
                            .add("Content-MD5", Base64.getEncoder().encodeToString(md5(jpeg)));
                    file(exchange, log, 200, "image/jpeg", jpeg);
                }
                case "/md5-bad.jpg" -> {
                    exchange.getResponseHeaders().add("Content-MD5", "nope");
                    file(exchange, log, 200, "image/jpeg", MediaFixtures.jpeg(64, 48));
                }
                case "/md5-bad24.jpg" -> {
                    // 24 characters that are no base64
                    exchange.getResponseHeaders().add("Content-MD5", "!!!!!!!!!!!!!!!!!!!!!!!!");
                    file(exchange, log, 200, "image/jpeg", MediaFixtures.jpeg(64, 48));
                }
                case "/moved.jpg" -> {
                    exchange.getResponseHeaders()
                            .add(
                                    "Location",
                                    "http://127.0.0.1:"
                                            + exchange.getLocalAddress().getPort() + "/dir/photo.jpg");
                    file(exchange, log, 301, "text/plain", new byte[0]);
                }
                case "/dir/padded.jpg" -> file(exchange, log, 200, "image/jpeg", padded(2048));
                case "/dir/large.jpg" -> file(exchange, log, 200, "image/jpeg", padded(1536 * 1024));
                case "/dir/pic.jpg" -> file(exchange, log, 200, "image/png", MediaFixtures.png(8, 8));
                case "/captioned.jpg" ->
                    file(
                            exchange,
                            log,
                            200,
                            "image/jpeg",
                            MediaFixtures.jpeg(8, 8, JpegMeta.none().withIptc(120, "Cap")));
                case "/nope.jpg" -> file(exchange, log, 404, "text/plain", "nope".getBytes(StandardCharsets.UTF_8));
                case "/created.jpg" -> {
                    byte[] xs = new byte[3000];
                    Arrays.fill(xs, (byte) 'x');
                    file(exchange, log, 201, "text/plain", xs);
                }
                case "/md5.jpg" -> {
                    exchange.getResponseHeaders().add("Content-MD5", "00000000000000000000000000000000");
                    file(exchange, log, 200, "image/jpeg", MediaFixtures.jpeg(8, 8));
                }
                case "/redirect.jpg" -> {
                    exchange.getResponseHeaders().add("Location", "http://127.0.0.1:22/a.jpg");
                    file(exchange, log, 302, "text/plain", new byte[0]);
                }
                case "/huge.jpg", "/huge-uncapped.jpg" -> huge(exchange, log);
                default -> file(exchange, log, 404, "text/plain", "no such file".getBytes(StandardCharsets.UTF_8));
            }
        }

        private static void file(HttpExchange exchange, Queue<String> log, int status, String type, byte[] body)
                throws IOException {
            log.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath() + " " + status);
            exchange.getResponseHeaders().add("Content-Type", type);
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }

        /** 50 MiB of JPEG-looking bytes, streamed until the client resets the connection. */
        private static void huge(HttpExchange exchange, Queue<String> log) throws IOException {
            String path = exchange.getRequestURI().getRawPath();
            log.add("GET " + path + " 200");
            exchange.getResponseHeaders().add("Content-Type", "image/jpeg");
            long total = 50L * 1024 * 1024;
            byte[] chunk = new byte[64 * 1024];
            Arrays.fill(chunk, (byte) 0x5a);
            long written = 0;
            exchange.sendResponseHeaders(200, total);
            try (OutputStream out = exchange.getResponseBody()) {
                while (written < total) {
                    out.write(chunk);
                    out.flush();
                    written += chunk.length;
                }
                log.add("DONE " + path + " " + written);
            } catch (IOException reset) {
                log.add("RESET " + path + " " + written);
            }
        }

        private static byte[] md5(byte[] body) {
            try {
                return MessageDigest.getInstance("MD5").digest(body);
            } catch (NoSuchAlgorithmException missing) {
                throw new IllegalStateException(missing);
            }
        }

        /** A JPEG followed by zero bytes up to {@code size}. */
        private static byte[] padded(int size) {
            byte[] jpeg = MediaFixtures.jpeg(8, 8);
            byte[] out = new byte[Math.max(size, jpeg.length)];
            System.arraycopy(jpeg, 0, out, 0, jpeg.length);
            return out;
        }
    }
}
