package org.quarkpress.restwp.runtime.wpv2;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.security.identity.SecurityIdentity;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.UserId;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaException;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestException;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.UploadedFile;

/**
 * How an upload's body is received as WordPress receives it: a multipart body's {@code file} part, or the raw body
 * with its headers checked in WordPress's order, and {@code Content-MD5} compared with the file's bytes; and how the
 * upload pipeline's refusals are answered for each body mode.
 */
class MediaUploadRequestTest {

    private static final byte[] PNG = "\u0089PNG fake image bytes".getBytes(StandardCharsets.ISO_8859_1);

    private static final String DISPOSITION = "attachment; filename=\"logo.png\"";

    private final List<Path> spooled = new ArrayList<>();

    private Path folder;

    private Media media;

    @BeforeEach
    void spoolFolder() throws IOException {
        folder = Files.createDirectories(
                Path.of("target", "task12-spool", UUID.randomUUID().toString()));
        media = (Media) Proxy.newProxyInstance(
                Media.class.getClassLoader(), new Class<?>[] {Media.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("spool")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    Path file = Files.createTempFile(folder, "spool-", ".tmp");
                    spooled.add(file);
                    try (var out = Files.newOutputStream(file)) {
                        ((InputStream) args[0]).transferTo(out);
                    }
                    return file;
                });
    }

    // ---- raw bodies

    @Test
    void aRawBodyIsSpooledWithItsTypeAndName() throws IOException {
        MediaUploadRequest.Body body = MediaUploadRequest.receive(
                raw(
                        PNG,
                        Map.of("Content-Type", List.of("image/png; q=1"), "Content-Disposition", List.of(DISPOSITION))),
                media);
        assertThat(body).isInstanceOf(MediaUploadRequest.Raw.class);
        MediaUploadRequest.Raw received = (MediaUploadRequest.Raw) body;
        assertThat(Files.readAllBytes(received.spooled())).isEqualTo(PNG);
        assertThat(received.size()).isEqualTo(PNG.length);
        // the first value, as sent, parameters kept
        assertThat(received.contentType()).isEqualTo("image/png; q=1");
        assertThat(received.fileName()).isEqualTo("logo.png");
    }

    @Test
    void rawHeaderChecksComeInWordPresssOrder() {
        // an empty body comes first, whatever the headers
        refusedRaw(new byte[0], Map.of(), 400, "rest_upload_no_data", "No data supplied.");
        refusedRaw(
                new byte[0],
                Map.of("Content-Type", List.of("image/png"), "Content-Disposition", List.of(DISPOSITION)),
                400,
                "rest_upload_no_data",
                "No data supplied.");
        // a body of exactly 0 is empty to PHP (WordPress, e05); 00 is not
        refusedRaw(
                "0".getBytes(StandardCharsets.ISO_8859_1),
                Map.of("Content-Type", List.of("image/png"), "Content-Disposition", List.of(DISPOSITION)),
                400,
                "rest_upload_no_data",
                "No data supplied.");
        // then the type
        refusedRaw(
                PNG,
                Map.of("Content-Disposition", List.of(DISPOSITION)),
                400,
                "rest_upload_no_content_type",
                "No Content-Type supplied.");
        refusedRaw(PNG, Map.of(), 400, "rest_upload_no_content_type", "No Content-Type supplied.");
        // then the disposition
        refusedRaw(
                PNG,
                Map.of("Content-Type", List.of("image/png")),
                400,
                "rest_upload_no_content_disposition",
                "No Content-Disposition supplied.");
        // then a file name in it
        refusedRaw(
                PNG,
                Map.of("Content-Type", List.of("image/png"), "Content-Disposition", List.of("attachment")),
                400,
                "rest_upload_invalid_disposition",
                "Invalid Content-Disposition supplied. Content-Disposition needs to be formatted as"
                        + " `attachment; filename=\"image.png\"` or similar.");
        // an empty line is a header all the same, without a name
        refusedRaw(
                PNG,
                Map.of("Content-Type", List.of("image/png"), "Content-Disposition", List.of("")),
                400,
                "rest_upload_invalid_disposition",
                "Invalid Content-Disposition supplied. Content-Disposition needs to be formatted as"
                        + " `attachment; filename=\"image.png\"` or similar.");
        // the hash is checked only once the name is known
        refusedRaw(
                PNG,
                Map.of(
                        "Content-Type",
                        List.of("image/png"),
                        "Content-Disposition",
                        List.of("attachment"),
                        "Content-MD5",
                        List.of("x")),
                400,
                "rest_upload_invalid_disposition",
                "Invalid Content-Disposition supplied. Content-Disposition needs to be formatted as"
                        + " `attachment; filename=\"image.png\"` or similar.");
        // nothing was spooled for a header refusal
        assertThat(spooled).isEmpty();
        assertThat(MediaUploadRequest.receive(
                        raw(
                                "00".getBytes(StandardCharsets.ISO_8859_1),
                                Map.of(
                                        "Content-Type",
                                        List.of("image/png"),
                                        "Content-Disposition",
                                        List.of(DISPOSITION))),
                        media))
                .isInstanceOf(MediaUploadRequest.Raw.class);
        // an empty type line is a type all the same
        assertThat(MediaUploadRequest.receive(
                        raw(PNG, Map.of("Content-Type", List.of(""), "Content-Disposition", List.of(DISPOSITION))),
                        media))
                .isEqualTo(new MediaUploadRequest.Raw(spooled.get(spooled.size() - 1), PNG.length, "", "logo.png"));
    }

    @Test
    void aMultipartBodyWithoutFilePartsIsAnEmptyRawBody() {
        // a multipart body has no raw body: with no file part at all there is no data
        RestException refused = assertRefused(
                () -> MediaUploadRequest.receive(new Upload(Map.of(), List.of(), Optional.empty()), media));
        assertError(refused.error(), 400, "rest_upload_no_data", "No data supplied.");
    }

    @Test
    void contentMd5IsLowerCaseHexOfTheFile() throws IOException {
        String hex = HexFormat.of().formatHex(md5(PNG));
        // lower-case hex: accepted, also with blanks around it
        assertThat(MediaUploadRequest.receive(rawWithMd5(hex), media)).isInstanceOf(MediaUploadRequest.Raw.class);
        assertThat(MediaUploadRequest.receive(rawWithMd5("  " + hex + "\t"), media))
                .isInstanceOf(MediaUploadRequest.Raw.class);
        // no header: not checked
        assertThat(MediaUploadRequest.receive(
                        raw(
                                PNG,
                                Map.of(
                                        "Content-Type",
                                        List.of("image/png"),
                                        "Content-Disposition",
                                        List.of(DISPOSITION))),
                        media))
                .isInstanceOf(MediaUploadRequest.Raw.class);
        int accepted = spooled.size();
        // upper-case hex, base64 and an empty value are compared and fail
        for (String wrong :
                List.of(hex.toUpperCase(Locale.ROOT), Base64.getEncoder().encodeToString(md5(PNG)), "", "   ")) {
            RestException refused = assertRefused(() -> MediaUploadRequest.receive(rawWithMd5(wrong), media));
            assertError(refused.error(), 412, "rest_upload_hash_mismatch", "Content hash did not match expected.");
        }
        // only the first value counts
        assertThat(MediaUploadRequest.receive(
                        raw(
                                PNG,
                                Map.of(
                                        "Content-Type",
                                        List.of("image/png"),
                                        "Content-Disposition",
                                        List.of(DISPOSITION),
                                        "Content-MD5",
                                        List.of(hex, "wrong"))),
                        media))
                .isInstanceOf(MediaUploadRequest.Raw.class);
        // each refused body's spool is deleted
        for (Path file : spooled.subList(accepted, accepted + 4)) {
            assertThat(file).doesNotExist();
        }
    }

    @Test
    void contentMd5OfAMultipartBodyIsTheFilePartsHash() throws IOException {
        Path part = part(PNG);
        String hex = HexFormat.of().formatHex(md5(PNG));
        UploadedFile file = new UploadedFile("file", "logo.png", Optional.of("image/png"), part, PNG.length);
        MediaUploadRequest.Body body = MediaUploadRequest.receive(
                new Upload(Map.of("Content-MD5", List.of(hex)), List.of(file), Optional.empty()), media);
        assertThat(body).isEqualTo(new MediaUploadRequest.Multipart(file));
        for (String wrong : List.of(hex.toUpperCase(Locale.ROOT), "")) {
            RestException refused = assertRefused(() -> MediaUploadRequest.receive(
                    new Upload(Map.of("Content-MD5", List.of(wrong)), List.of(file), Optional.empty()), media));
            assertError(refused.error(), 412, "rest_upload_hash_mismatch", "Content hash did not match expected.");
        }
        // the part is Quarkus's: never deleted here
        assertThat(part).exists();
        assertThat(spooled).isEmpty();
    }

    // ---- multipart bodies

    @Test
    void theFilePartIsTheOneNamedFile() throws IOException {
        UploadedFile other = new UploadedFile("upload", "a.png", Optional.of("image/png"), part(PNG), PNG.length);
        UploadedFile file = new UploadedFile("file", "b.png", Optional.of("image/png"), part(PNG), PNG.length);
        assertThat(MediaUploadRequest.receive(new Upload(Map.of(), List.of(other, file), Optional.empty()), media))
                .isEqualTo(new MediaUploadRequest.Multipart(file));
        assertThat(MediaUploadRequest.filePart(new Upload(Map.of(), List.of(other, file), Optional.empty())))
                .contains(file);
        assertThat(MediaUploadRequest.filePart(new Upload(Map.of(), List.of(other), Optional.empty())))
                .isEmpty();
    }

    @Test
    void multipartWithoutTheFilePart() throws IOException {
        UploadedFile other = new UploadedFile("upload", "a.png", Optional.of("image/png"), part(PNG), PNG.length);
        RestException refused = assertRefused(
                () -> MediaUploadRequest.receive(new Upload(Map.of(), List.of(other), Optional.empty()), media));
        assertError(refused.error(), 500, "rest_upload_unknown_error", "Specified file failed upload test.");
    }

    @Test
    void anEmptyFilePartIsNoFileUploaded() {
        UploadedFile empty = UploadedFile.notUploaded("file", Optional.of("application/octet-stream"));
        RestException refused = assertRefused(
                () -> MediaUploadRequest.receive(new Upload(Map.of(), List.of(empty), Optional.empty()), media));
        assertError(refused.error(), 500, "rest_upload_unknown_error", "No file was uploaded.");
    }

    @Test
    void bracketedFilePartsAnswerTheNestedErrorCodes() throws IOException {
        assertThat(nested("file[]")).isEqualTo("[0]");
        assertThat(nested("file[a]")).isEqualTo("{\"a\":0}");
        assertThat(nested("file[]", "file[]")).isEqualTo("[0,0]");
        assertThat(nested("file[a][b]")).isEqualTo("{\"a\":{\"b\":0}}");
        assertThat(nested("file[1]")).isEqualTo("{\"1\":0}");
        // a part sent without a file name is PHP's "no file" code
        UploadedFile empty = UploadedFile.notUploaded("file[]", Optional.empty());
        MediaUploadRequest.NestedUploadError error = assertNested(
                () -> MediaUploadRequest.receive(new Upload(Map.of(), List.of(empty), Optional.empty()), media));
        assertThat(error.answer().body().get("message").toString()).isEqualTo("[4]");
        // a part named exactly file wins over bracketed ones
        UploadedFile file = new UploadedFile("file", "b.png", Optional.of("image/png"), part(PNG), PNG.length);
        UploadedFile bracketed = new UploadedFile("file[]", "a.png", Optional.of("image/png"), part(PNG), PNG.length);
        assertThat(MediaUploadRequest.receive(new Upload(Map.of(), List.of(bracketed, file), Optional.empty()), media))
                .isEqualTo(new MediaUploadRequest.Multipart(file));
        // another base name is no file part
        UploadedFile files = new UploadedFile("files[]", "a.png", Optional.of("image/png"), part(PNG), PNG.length);
        RestException refused = assertRefused(
                () -> MediaUploadRequest.receive(new Upload(Map.of(), List.of(files), Optional.empty()), media));
        assertError(refused.error(), 500, "rest_upload_unknown_error", "Specified file failed upload test.");
    }

    // ---- refusals of the pipeline

    @Test
    void pipelineRefusalsAreMappedByBodyMode() throws IOException {
        MediaUploadRequest.Body multipart = new MediaUploadRequest.Multipart(
                new UploadedFile("file", "a.png", Optional.of("image/png"), part(PNG), PNG.length));
        MediaUploadRequest.Body raw = new MediaUploadRequest.Raw(part(PNG), PNG.length, "image/png", "a.png");
        String message = "Sorry, you are not allowed to upload this file type.";
        assertError(
                MediaUploadRequest.pipelineRefusal(MediaException.upload(message), multipart, 403)
                        .error(),
                500,
                "rest_upload_unknown_error",
                message);
        assertError(
                MediaUploadRequest.pipelineRefusal(MediaException.upload(message), raw, 403)
                        .error(),
                500,
                "rest_upload_sideload_error",
                message);
        for (String code : List.of(
                "rest_upload_limited_space",
                "rest_upload_file_too_big",
                "rest_upload_user_quota_exceeded",
                "rest_upload_image_type_not_supported")) {
            for (MediaUploadRequest.Body body : List.of(multipart, raw)) {
                assertError(
                        MediaUploadRequest.pipelineRefusal(new MediaException(code, "m " + code), body, 403)
                                .error(),
                        400,
                        code,
                        "m " + code);
            }
        }
        String cannotEdit = "Sorry, you are not allowed to upload media to this post.";
        assertError(
                MediaUploadRequest.pipelineRefusal(new MediaException("rest_cannot_edit", cannotEdit), raw, 401)
                        .error(),
                401,
                "rest_cannot_edit",
                cannotEdit);
        assertError(
                MediaUploadRequest.pipelineRefusal(new MediaException("rest_cannot_edit", cannotEdit), raw, 403)
                        .error(),
                403,
                "rest_cannot_edit",
                cannotEdit);
        RestError invalidParent = MediaUploadRequest.pipelineRefusal(
                        new MediaException("rest_invalid_param", "Invalid parent type."), multipart, 403)
                .error();
        assertThat(invalidParent.toJson().toString())
                .isEqualTo("{\"code\":\"rest_invalid_param\",\"message\":\"Invalid parent type.\",\"data\":"
                        + "{\"status\":400}}");
    }

    // ---- helpers

    private String nested(String... names) throws IOException {
        List<UploadedFile> parts = new ArrayList<>();
        for (String name : names) {
            parts.add(new UploadedFile(name, "a.png", Optional.of("image/png"), part(PNG), PNG.length));
        }
        MediaUploadRequest.NestedUploadError error =
                assertNested(() -> MediaUploadRequest.receive(new Upload(Map.of(), parts, Optional.empty()), media));
        assertThat(error.answer().status()).isEqualTo(500);
        JsonNode body = error.answer().body();
        assertThat(body.get("code").asText()).isEqualTo("rest_upload_unknown_error");
        assertThat(body.get("data").toString()).isEqualTo("{\"status\":500}");
        assertThat(body.properties().stream().map(Map.Entry::getKey).toList())
                .containsExactly("code", "message", "data");
        return body.get("message").toString();
    }

    private static MediaUploadRequest.NestedUploadError assertNested(Receive receive) {
        try {
            receive.run();
        } catch (MediaUploadRequest.NestedUploadError error) {
            return error;
        }
        throw new AssertionError("no nested upload error");
    }

    private RestException assertRefused(Receive receive) {
        try {
            receive.run();
        } catch (RestException refused) {
            return refused;
        }
        throw new AssertionError("not refused");
    }

    @FunctionalInterface
    private interface Receive {
        void run();
    }

    private void refusedRaw(byte[] body, Map<String, List<String>> headers, int status, String code, String message) {
        RestException refused = assertRefused(() -> MediaUploadRequest.receive(raw(body, headers), media));
        assertError(refused.error(), status, code, message);
    }

    private static void assertError(RestError error, int status, String code, String message) {
        assertThat(error.code()).isEqualTo(code);
        assertThat(error.message()).isEqualTo(message);
        assertThat(error.status()).isEqualTo(status);
        assertThat(error.toJson().get("data").toString()).isEqualTo("{\"status\":" + status + "}");
    }

    private static Upload rawWithMd5(String md5) {
        return raw(
                PNG,
                Map.of(
                        "Content-Type",
                        List.of("image/png"),
                        "Content-Disposition",
                        List.of(DISPOSITION),
                        "Content-MD5",
                        List.of(md5)));
    }

    private static Upload raw(byte[] body, Map<String, List<String>> headers) {
        return new Upload(headers, List.of(), Optional.of(new ByteArrayInputStream(body)));
    }

    private Path part(byte[] bytes) throws IOException {
        return Files.write(Files.createTempFile(folder, "part-", ".bin"), bytes);
    }

    private static byte[] md5(byte[] bytes) {
        try {
            return MessageDigest.getInstance("MD5").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A POST to the media collection with the given headers, file parts and raw body. */
    private static final class Upload implements RestRequest {

        private final Map<String, List<String>> headers = new LinkedHashMap<>();
        private final List<UploadedFile> files;
        private final Optional<InputStream> raw;
        private final ObjectNode params = JsonNodeFactory.instance.objectNode();

        Upload(Map<String, List<String>> headers, List<UploadedFile> files, Optional<InputStream> raw) {
            headers.forEach((name, values) -> this.headers.put(name.toLowerCase(Locale.ROOT), values));
            this.files = files;
            this.raw = raw;
        }

        @Override
        public List<String> headerValues(String name) {
            return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
        }

        @Override
        public List<UploadedFile> files() {
            return files;
        }

        @Override
        public Optional<InputStream> rawBody() {
            return raw;
        }

        @Override
        public String method() {
            return "POST";
        }

        @Override
        public String route() {
            return "/wp/v2/media";
        }

        @Override
        public String path() {
            return "/wp/v2/media";
        }

        @Override
        public Map<String, String> urlParams() {
            return Map.of();
        }

        @Override
        public Optional<JsonNode> param(String name) {
            return Optional.ofNullable(params.get(name));
        }

        @Override
        public boolean hasParam(String name) {
            return params.has(name);
        }

        @Override
        public ObjectNode queryParams() {
            return params;
        }

        @Override
        public String context() {
            return "view";
        }

        @Override
        public SecurityIdentity identity() {
            return null;
        }

        @Override
        public Optional<UserId> user() {
            return Optional.empty();
        }

        @Override
        public int authCode() {
            return 403;
        }
    }
}
