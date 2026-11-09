package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.RestAssured;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * A handler reads the request's body as sent: a body of any type but JSON, form or none is a live stream, never held
 * in memory, bounded by Quarkus's body limit; every value of a repeated header reaches it.
 */
class RawBodyDbTest {

    static final int MIB = 1024 * 1024;

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(
                    RawBodyDbTest.class,
                    RawBodyDbTest.Streamed.class,
                    RawBodyDbTest.Streamed.Reply.class,
                    RawBodyDbTest.Generated.class,
                    TestRoutes.class,
                    TestRoutes.PlainResource.class)
            .overrideConfigKey("quarkus.http.limits.max-body-size", "64M");

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void rawBodyAndRepeatedHeadersReachTheHandler() {
        Streamed.Reply reply = Streamed.post(
                "/wp-json/qp-test/v1/echo",
                "Content-Type: image/png\r\nContent-Disposition: attachment; filename=a.png\r\n"
                        + "Content-Disposition: inline; filename=b.png\r\n",
                70_000,
                false);
        assertThat(reply.status()).isEqualTo(200);
        JsonNode echo = reply.json();
        assertThat(echo.get("length").asLong()).isEqualTo(70_000);
        assertThat(echo.get("md5").asText()).isEqualTo(reply.sentMd5());
        assertThat(echo.get("headers").toString())
                .isEqualTo("[\"attachment; filename=a.png\",\"inline; filename=b.png\"]");
        assertThat(echo.get("params").toString()).isEqualTo("[]");
        assertThat(echo.get("files").toString()).isEqualTo("[]");
    }

    @Test
    void aRawBodyIsStreamedNotBuffered() {
        Streamed.Reply reply =
                Streamed.post("/wp-json/qp-test/v1/echo", "Content-Type: image/png\r\n", 48 * MIB, false);
        assertThat(reply.status()).isEqualTo(200);
        JsonNode echo = reply.json();
        assertThat(echo.get("length").asLong()).isEqualTo(48L * MIB);
        assertThat(echo.get("md5").asText()).isEqualTo(reply.sentMd5());
        assertThat(echo.get("allocated").asLong()).isLessThan(16L * MIB);
        assertThat(echo.get("stream").asText()).isNotEqualTo("ByteArrayInputStream");
    }

    @Test
    void aJsonBodyIsStillParsedAndReadable() {
        JsonNode echo = Streamed.post(
                        "/wp-json/qp-test/v1/echo",
                        "Content-Type: application/json\r\n",
                        "{\"title\":\"x\"}".getBytes(StandardCharsets.UTF_8),
                        false)
                .json();
        assertThat(echo.get("params").toString()).isEqualTo("[\"title\"]");
        assertThat(echo.get("length").asLong()).isEqualTo(13);
        assertThat(echo.get("stream").asText()).isEqualTo("ByteArrayInputStream");
        // a form body and a body without a type are read whole as well
        JsonNode form = Streamed.post(
                        "/wp-json/qp-test/v1/echo",
                        "Content-Type: application/x-www-form-urlencoded\r\n",
                        "a[b]=1&post=11".getBytes(StandardCharsets.UTF_8),
                        false)
                .json();
        assertThat(form.get("params").toString()).isEqualTo("[\"a\",\"post\"]");
        assertThat(form.get("length").asLong()).isEqualTo(14);
        JsonNode untyped = Streamed.post("/wp-json/qp-test/v1/echo", "", "abc".getBytes(StandardCharsets.UTF_8), false)
                .json();
        assertThat(untyped.get("length").asLong()).isEqualTo(3);
        assertThat(untyped.get("stream").asText()).isEqualTo("ByteArrayInputStream");
    }

    @Test
    void aChunkedBodyAboveTheLimitIsRefused() {
        Streamed.Reply reply = Streamed.post("/wp-json/qp-test/v1/echo", "Content-Type: image/png\r\n", 65 * MIB, true);
        assertThat(reply.status()).isEqualTo(413);
        assertThat(reply.body()).isEmpty();
        // a declared length above the limit is refused before the body is read
        Streamed.Reply declared =
                Streamed.post("/wp-json/qp-test/v1/echo", "Content-Type: image/png\r\n", 64 * MIB + 1, false);
        assertThat(declared.status()).isEqualTo(413);
        assertThat(declared.body()).isEmpty();
    }

    @Test
    void aChunkedJsonBodyAboveTheLimitIsRefused() {
        // a JSON body is read whole into memory: the limit cuts it there too
        Streamed.Reply reply =
                Streamed.post("/wp-json/qp-test/v1/echo", "Content-Type: application/json\r\n", 65 * MIB, true);
        assertThat(reply.status()).as(reply.head()).isEqualTo(413);
        assertThat(reply.body()).isEmpty();
    }

    @Test
    void aLargeBodyReachesASubdirectorySite() {
        AgroalDataSource data = Jdbc.dataSource();
        String news = "news" + UUID.randomUUID().toString().substring(0, 8);
        Jdbc.update(data, "insert into qp_site (domain, path, name) values ('localhost', ?, 'news')", "/" + news + "/");
        for (boolean chunked : List.of(false, true)) {
            Streamed.Reply reply = Streamed.post(
                    "/" + news + "/wp-json/qp-test/v1/echo", "Content-Type: image/png\r\n", 48 * MIB, chunked);
            assertThat(reply.status()).isEqualTo(200);
            JsonNode echo = reply.json();
            assertThat(echo.get("length").asLong()).isEqualTo(48L * MIB);
            assertThat(echo.get("md5").asText()).isEqualTo(reply.sentMd5());
        }
    }

    /** HTTP/1.1 requests whose body is written as it is generated, never held whole by the client. */
    static final class Streamed {

        /** The answer's status, headers and body, and the MD5 of the body sent (lower-case hex). */
        record Reply(int status, String head, byte[] body, String sentMd5) {

            JsonNode json() {
                assertThat(status).as(head).isEqualTo(200);
                try {
                    return JSON.readTree(body);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }

        private Streamed() {}

        /** POSTs {@code length} generated bytes, with a declared length or chunked. */
        static Reply post(String target, String headers, long length, boolean chunked) {
            return send("POST", target, headers, new Generated(length), length, chunked);
        }

        static Reply post(String target, String headers, byte[] body, boolean chunked) {
            return send("POST", target, headers, new ByteArrayInputStream(body), body.length, chunked);
        }

        static Reply send(
                String method, String target, String headers, InputStream body, long length, boolean chunked) {
            MessageDigest md5 = md5();
            try (Socket socket = new Socket("localhost", RestAssured.port)) {
                socket.setSoTimeout(60_000);
                OutputStream out = socket.getOutputStream();
                String head = method + " " + target + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n" + headers
                        + (chunked ? "Transfer-Encoding: chunked\r\n" : "Content-Length: " + length + "\r\n")
                        + "\r\n";
                try {
                    out.write(head.getBytes(StandardCharsets.ISO_8859_1));
                    byte[] buffer = new byte[256 * 1024];
                    int read;
                    while ((read = body.read(buffer)) > 0) {
                        md5.update(buffer, 0, read);
                        if (chunked) {
                            out.write((Integer.toHexString(read) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                        }
                        out.write(buffer, 0, read);
                        if (chunked) {
                            out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                        }
                    }
                    if (chunked) {
                        out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    }
                    out.flush();
                } catch (IOException refusedWhileSending) {
                    // the server answered and closed the connection before the body was sent: read its answer
                }
                ByteArrayOutputStream reply = new ByteArrayOutputStream();
                try {
                    socket.getInputStream().transferTo(reply);
                } catch (IOException resetAfterTheAnswer) {
                    // a reset after the answer arrived
                }
                return parse(reply.toByteArray(), HexFormat.of().formatHex(md5.digest()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static Reply parse(byte[] reply, String sentMd5) {
            String text = new String(reply, StandardCharsets.ISO_8859_1);
            int end = text.indexOf("\r\n\r\n");
            assertThat(end).as("an answer: " + text).isPositive();
            String head = text.substring(0, end);
            int space = head.indexOf(' ');
            int status = Integer.parseInt(head.substring(space + 1, space + 4));
            byte[] body = Arrays.copyOfRange(reply, end + 4, reply.length);
            if (head.toLowerCase(Locale.ROOT).contains("transfer-encoding: chunked")) {
                body = unchunk(body);
            }
            return new Reply(status, head, body, sentMd5);
        }

        private static byte[] unchunk(byte[] chunked) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int at = 0;
            while (at < chunked.length) {
                int lineEnd = at;
                while (chunked[lineEnd] != '\r') {
                    lineEnd++;
                }
                int size = Integer.parseInt(new String(chunked, at, lineEnd - at, StandardCharsets.ISO_8859_1), 16);
                if (size == 0) {
                    break;
                }
                out.write(chunked, lineEnd + 2, size);
                at = lineEnd + 2 + size + 2;
            }
            return out.toByteArray();
        }

        private static MessageDigest md5() {
            try {
                return MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** {@code length} bytes of a fixed pattern, generated as they are read. */
    static final class Generated extends InputStream {

        private final long length;
        private long position;

        Generated(long length) {
            this.length = length;
        }

        @Override
        public int read() {
            return position < length ? (int) (pattern(position++) & 0xFF) : -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int count) {
            if (position >= length) {
                return -1;
            }
            int n = (int) Math.min(count, length - position);
            for (int i = 0; i < n; i++) {
                buffer[offset + i] = pattern(position++);
            }
            return n;
        }

        private static byte pattern(long at) {
            return (byte) (at * 31 + (at >>> 9) + (at >>> 17));
        }
    }
}
