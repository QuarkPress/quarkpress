package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.RestAssured;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * A multipart POST gives the handler its text parts as parameters, where a form's fields sit, and its file parts as
 * files, stored by Quarkus for the time of the request; any other method gets neither, as PHP fills its form fields
 * and files only for POST.
 */
class MultipartBodyDbTest {

    private static final String BOUNDARY = "qp-boundary-7d1";

    private static final int MIB = 1024 * 1024;

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(
                    MultipartBodyDbTest.class,
                    RawBodyDbTest.class,
                    RawBodyDbTest.Streamed.class,
                    RawBodyDbTest.Streamed.Reply.class,
                    RawBodyDbTest.Generated.class,
                    TestRoutes.class,
                    TestRoutes.PlainResource.class)
            .overrideConfigKey("quarkus.http.limits.max-body-size", "512M")
            .overrideConfigKey("quarkus.http.body.uploads-directory", "target/uploads-multipart");

    @Test
    void textPartsAreParametersAndFilePartsAreFiles() {
        byte[] photo = bytes(1000);
        JsonNode echo = multipart(
                        "POST",
                        filePart("file", "photo.jpg", "image/jpeg", photo),
                        textPart("post", "11"),
                        textPart("a[b]", "1"))
                .json();
        assertThat(echo.get("params").toString()).isEqualTo("[\"a\",\"post\"]");
        assertThat(echo.get("values").toString()).isEqualTo("{\"a\":{\"b\":\"1\"},\"post\":\"11\"}");
        assertThat(echo.get("files")).hasSize(1);
        JsonNode file = echo.get("files").get(0);
        assertThat(file.get("field").asText()).isEqualTo("file");
        assertThat(file.get("fileName").asText()).isEqualTo("photo.jpg");
        assertThat(file.get("contentType").asText()).isEqualTo("image/jpeg");
        assertThat(file.get("size").asLong()).isEqualTo(1000);
        assertThat(file.get("exists").asBoolean()).isTrue();
        assertThat(echo.get("stream").isNull()).isTrue();
        assertThat(echo.has("length")).isFalse();
    }

    @Test
    void aFilesContentTypeIsKeptAsSent() {
        JsonNode echo = multipart("POST", filePart("file", "a.txt", "text/plain; charset=ISO-8859-1", bytes(3)))
                .json();
        assertThat(echo.get("files").get(0).get("contentType").asText()).isEqualTo("text/plain; charset=ISO-8859-1");
        JsonNode untyped =
                multipart("POST", filePart("file", "a.bin", null, bytes(3))).json();
        assertThat(untyped.get("files").get(0).get("contentType").isNull()).isTrue();
    }

    @Test
    void textPartsAddUpAsPhpsFormFieldsDo() {
        JsonNode echo = multipart(
                        "POST",
                        textPart("tags[]", "1"),
                        textPart("tags[]", "2"),
                        textPart("title", "x"),
                        textPart("title", "y"))
                .json();
        assertThat(echo.get("values").toString()).isEqualTo("{\"tags\":{\"0\":\"1\",\"1\":\"2\"},\"title\":\"y\"}");
    }

    @Test
    void multipartFilesAreGoneAfterTheAnswer() throws InterruptedException {
        JsonNode echo = multipart("POST", filePart("file", "photo.jpg", "image/jpeg", bytes(1000)))
                .json();
        Path file = Path.of(echo.get("files").get(0).get("file").asText());
        assertThat(echo.get("files").get(0).get("exists").asBoolean()).isTrue();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (Files.exists(file) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(file).doesNotExist();
    }

    @Test
    void aFileUnderAnotherFieldIsStillAFile() {
        JsonNode echo = multipart("POST", filePart("upload", "photo.jpg", "image/jpeg", bytes(10)))
                .json();
        assertThat(echo.get("files")).hasSize(1);
        assertThat(echo.get("files").get(0).get("field").asText()).isEqualTo("upload");
        assertThat(echo.get("params").toString()).isEqualTo("[]");
    }

    @Test
    void putMultipartHasNoParameters() {
        JsonNode echo = multipart(
                        "PUT", textPart("post", "11"), filePart("file", "photo.jpg", "image/jpeg", bytes(1000)))
                .json();
        assertThat(echo.get("params").toString()).isEqualTo("[]");
        assertThat(echo.get("files").toString()).isEqualTo("[]");
        assertThat(echo.get("stream").isNull()).isTrue();
    }

    @Test
    void aPartWithAnEmptyFileNameIsAFileThatWasNotUploaded() {
        // as PHP lists a file field sent empty: a file part without a file, never a parameter
        JsonNode echo = multipart(
                        "POST",
                        filePart("file", "", "application/octet-stream", new byte[0]),
                        filePart("other", "dir/", "image/png", bytes(5)))
                .json();
        assertThat(echo.get("params").toString()).isEqualTo("[]");
        assertThat(echo.get("files")).hasSize(2);
        for (JsonNode file : echo.get("files")) {
            assertThat(file.get("fileName").asText()).isEmpty();
            assertThat(file.get("size").asLong()).isZero();
        }
        assertThat(echo.get("files").get(0).get("field").asText()).isEqualTo("file");
        assertThat(echo.get("files").get(0).get("contentType").asText()).isEqualTo("application/octet-stream");
        assertThat(echo.get("files").get(1).get("field").asText()).isEqualTo("other");
    }

    @Test
    void aLongTextPartIsAParameter() {
        String description = "d".repeat(100 * 1024);
        RawBodyDbTest.Streamed.Reply reply = multipart("POST", textPart("description", description));
        assertThat(reply.status()).as(reply.head()).isEqualTo(200);
        JsonNode echo = reply.json();
        assertThat(echo.get("params").toString()).isEqualTo("[\"description\"]");
        assertThat(echo.get("values").get("description").asText()).hasSize(102_400);
    }

    /**
     * A file part is written to Quarkus's uploads directory as it arrives, never held in memory: while a 128 MiB part
     * is still arriving, its file on disk grows past 64 MiB and the heap kept after a collection does not grow by more
     * than 32 MiB; once complete, the handler gets the whole part.
     */
    @Test
    void aMultipartFilePartIsStreamedNotBuffered() throws Exception {
        // read from the running application: this class is loaded twice, so a static value would differ
        Path uploads =
                Path.of(ConfigProvider.getConfig().getValue("quarkus.http.body.uploads-directory", String.class));
        String head = "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"big.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n";
        String tail = "\r\n--" + BOUNDARY + "--\r\n";
        long part = 128L * MIB;
        long held = 120L * MIB;
        AtomicLong written = new AtomicLong();
        // only files that appear during this test count: a file left by an earlier run is ignored
        Set<Path> earlier = new HashSet<>();
        if (Files.isDirectory(uploads)) {
            try (Stream<Path> files = Files.list(uploads)) {
                files.forEach(earlier::add);
            }
        }
        try (Socket socket = new Socket("localhost", RestAssured.port)) {
            socket.setSoTimeout(60_000);
            OutputStream out = socket.getOutputStream();
            long heapBefore = heapAfterCollection();
            Thread writer = new Thread(() -> {
                try {
                    out.write(("POST /wp-json/qp-test/v1/echo HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                                    + "Content-Type: multipart/form-data; boundary=" + BOUNDARY + "\r\nContent-Length: "
                                    + (head.length() + part + tail.length()) + "\r\n\r\n" + head)
                            .getBytes(StandardCharsets.ISO_8859_1));
                    byte[] chunk = new byte[64 * 1024];
                    Arrays.fill(chunk, (byte) 7);
                    while (written.get() < held) {
                        out.write(chunk);
                        written.addAndGet(chunk.length);
                    }
                    out.flush();
                } catch (IOException closed) {
                    // the socket closed under the writer: the assertions below report it
                }
            });
            writer.setDaemon(true);
            writer.start();
            long[] largest = {0};
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (System.nanoTime() < deadline && largest[0] < 64L * MIB) {
                largest[0] = Math.max(largest[0], largestFile(uploads, earlier));
                Thread.sleep(20);
            }
            long heapGrowth = heapAfterCollection() - heapBefore;
            System.out.println("multipart receipt: written " + written.get() / MIB + " MiB, part file "
                    + largest[0] / MIB + " MiB, heap kept " + heapGrowth / MIB + " MiB");
            assertThat(largest[0])
                    .as("the part's file grows while the body arrives")
                    .isGreaterThanOrEqualTo(64L * MIB);
            assertThat(heapGrowth).as("heap kept while the body arrives").isLessThan(32L * MIB);

            writer.join(20_000);
            assertThat(written.get()).isEqualTo(held);
            byte[] rest = new byte[(int) (part - held)];
            out.write(rest);
            out.write(tail.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            String answer = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertThat(answer).startsWith("HTTP/1.1 200").contains("\"size\":" + part);
        }
    }

    private static long heapAfterCollection() {
        System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    /** The size of the largest file in {@code folder} that is not one of {@code earlier}. */
    private static long largestFile(Path folder, Set<Path> earlier) {
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(file -> !earlier.contains(file))
                    .mapToLong(file -> {
                        try {
                            return Files.size(file);
                        } catch (IOException gone) {
                            return 0;
                        }
                    })
                    .max()
                    .orElse(0);
        } catch (IOException | UncheckedIOException none) {
            return 0;
        }
    }

    static RawBodyDbTest.Streamed.Reply multipart(String method, byte[]... parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            body.writeBytes(part);
        }
        body.writeBytes(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.ISO_8859_1));
        byte[] bytes = body.toByteArray();
        return RawBodyDbTest.Streamed.send(
                method,
                "/wp-json/qp-test/v1/echo",
                "Content-Type: multipart/form-data; boundary=" + BOUNDARY + "\r\n",
                new ByteArrayInputStream(bytes),
                bytes.length,
                false);
    }

    static byte[] textPart(String name, String value) {
        return ("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] filePart(String name, String fileName, String contentType, byte[] content) {
        ByteArrayOutputStream part = new ByteArrayOutputStream();
        part.writeBytes(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\""
                        + fileName + "\"\r\n" + (contentType == null ? "" : "Content-Type: " + contentType + "\r\n")
                        + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
        part.writeBytes(content);
        part.writeBytes("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        return part.toByteArray();
    }

    private static byte[] bytes(int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) 0x5A);
        return bytes;
    }
}
