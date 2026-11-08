package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.QuarkusExtensionTest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * A multipart POST gives the handler its text parts as parameters, where a form's fields sit, and its file parts as
 * files, stored by Quarkus for the time of the request; any other method gets neither, as PHP fills its form fields
 * and files only for POST.
 */
class MultipartBodyDbTest {

    private static final String BOUNDARY = "qp-boundary-7d1";

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(
            MultipartBodyDbTest.class,
            RawBodyDbTest.class,
            RawBodyDbTest.Streamed.class,
            RawBodyDbTest.Streamed.Reply.class,
            RawBodyDbTest.Generated.class,
            TestRoutes.class,
            TestRoutes.PlainResource.class);

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
    void aPartWithAnEmptyFileNameIsDropped() {
        // Quarkus treats a part whose file name is empty as no file: it is neither a file nor a parameter
        JsonNode echo = multipart("POST", filePart("file", "", "application/octet-stream", new byte[0]))
                .json();
        assertThat(echo.get("files").toString()).isEqualTo("[]");
        assertThat(echo.get("params").toString()).isEqualTo("[]");
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
