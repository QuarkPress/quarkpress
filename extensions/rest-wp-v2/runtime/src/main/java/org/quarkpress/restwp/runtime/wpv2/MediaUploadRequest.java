package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaException;
import org.quarkpress.restwp.Links;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestException;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.RestResponse;
import org.quarkpress.restwp.UploadedFile;
import org.quarkpress.restwp.runtime.PhpQuery;

/**
 * The file of a media upload, received as WordPress receives it, and the answers to its refusals.
 *
 * <p>A request with any file part is a multipart upload: its file is the part named exactly {@code file}. Otherwise the
 * body itself is the file (a JSON body included), which must carry a {@code Content-Type} and a
 * {@code Content-Disposition} naming the file; it is spooled to the media storage's temporary area. In both modes a
 * {@code Content-MD5} header, when present, must be the lower-case hexadecimal MD5 of the file's bytes.
 */
final class MediaUploadRequest {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private static final String FILE = "file";

    /** PHP's error code of an uploaded file. */
    private static final int UPLOAD_OK = 0;

    /** PHP's error code of a file field sent without a file. */
    private static final int NO_FILE = 4;

    private MediaUploadRequest() {}

    /** The file of an upload, as its body carries it. */
    sealed interface Body permits Multipart, Raw {}

    /** A multipart upload's {@code file} part, which Quarkus stores and deletes. */
    record Multipart(UploadedFile file) implements Body {

        Multipart {
            Objects.requireNonNull(file, "file");
        }
    }

    /**
     * A raw-body upload.
     *
     * @param spooled the body, spooled to the media storage's temporary area; the caller deletes it
     * @param size the body's size in bytes
     * @param contentType the first {@code Content-Type} value as sent, parameters kept
     * @param fileName the file name of the {@code Content-Disposition} header, as given
     */
    record Raw(Path spooled, long size, String contentType, String fileName) implements Body {

        Raw {
            Objects.requireNonNull(spooled, "spooled");
            Objects.requireNonNull(contentType, "contentType");
            Objects.requireNonNull(fileName, "fileName");
        }
    }

    /**
     * WordPress's answer to a multipart upload whose only file parts are named {@code file[…]}: PHP turns them into
     * nested upload arrays, and the error then carries their nested error codes as its message, a JSON array or object
     * rather than a text, which a {@link RestError} cannot hold.
     */
    static final class NestedUploadError extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient RestResponse answer;

        NestedUploadError(JsonNode codes) {
            super("rest_upload_unknown_error: " + codes);
            ObjectNode body = NODES.objectNode();
            body.put("code", "rest_upload_unknown_error");
            body.set("message", codes);
            body.putObject("data").put("status", 500);
            this.answer = new RestResponse(500, body, Map.of(), Links.none());
        }

        /** The answer: 500 with {@code code}, the nested codes as {@code message}, and {@code data}. */
        RestResponse answer() {
            return answer;
        }
    }

    /**
     * The upload's file, its refusals in WordPress's order. A multipart upload: a {@code Content-MD5} that does not
     * match the {@code file} part (412), no {@code file} part (500), a {@code file} part without a file (500). A raw
     * upload: an empty body, no {@code Content-Type}, no {@code Content-Disposition}, no file name in it (each 400),
     * then a {@code Content-MD5} that does not match the body (412, its spool deleted).
     *
     * @throws RestException with WordPress's error for each refusal
     * @throws NestedUploadError for bracketed {@code file[…]} parts alone
     */
    static Body receive(RestRequest request, Media media) {
        List<UploadedFile> files = request.files();
        Optional<String> md5 = expectedMd5(request);
        if (!files.isEmpty()) {
            Optional<UploadedFile> file = filePart(files);
            if (md5.isPresent()
                    && !(file.isPresent()
                            && file.get().uploaded()
                            && md5.get().equals(md5(file.get().file())))) {
                throw new RestException(hashMismatch());
            }
            if (file.isEmpty()) {
                Optional<JsonNode> nested = nestedCodes(files);
                if (nested.isPresent()) {
                    throw new NestedUploadError(nested.get());
                }
                throw new RestException(
                        RestError.of("rest_upload_unknown_error", "Specified file failed upload test.", 500));
            }
            if (!file.get().uploaded()) {
                throw new RestException(RestError.of("rest_upload_unknown_error", "No file was uploaded.", 500));
            }
            return new Multipart(file.get());
        }
        return raw(request, media, md5);
    }

    /** The part named exactly {@code file} (the last one, as PHP keeps it); empty when there is none. */
    static Optional<UploadedFile> filePart(RestRequest request) {
        return filePart(request.files());
    }

    private static Optional<UploadedFile> filePart(List<UploadedFile> files) {
        UploadedFile found = null;
        for (UploadedFile file : files) {
            if (file.field().equals(FILE)) {
                found = file;
            }
        }
        return Optional.ofNullable(found);
    }

    /**
     * WordPress's answer to a refusal of the upload pipeline: a refusal of the upload itself is a 500 whose code names
     * the body mode; the network's size limits and an image the server cannot make sizes for are 400; a parent the
     * caller may not edit takes the caller's status (401 or 403); a parent of the wrong type is a 400 without details.
     */
    static RestException pipelineRefusal(MediaException refused, Body body, int authCode) {
        String code = refused.code();
        String message = refused.getMessage();
        return new RestException(
                switch (code) {
                    case "upload_error" ->
                        RestError.of(
                                body instanceof Raw ? "rest_upload_sideload_error" : "rest_upload_unknown_error",
                                message,
                                500);
                    case "rest_upload_limited_space",
                            "rest_upload_file_too_big",
                            "rest_upload_user_quota_exceeded",
                            "rest_upload_image_type_not_supported",
                            "rest_invalid_param" -> RestError.of(code, message, 400);
                    case "rest_cannot_edit" -> RestError.of(code, message, authCode);
                    default -> RestError.of(code, message, 500);
                });
    }

    // ---- raw bodies

    private static Body raw(RestRequest request, Media media, Optional<String> md5) {
        Optional<InputStream> stream = request.rawBody();
        PushbackInputStream body = null;
        if (stream.isPresent()) {
            body = new PushbackInputStream(stream.get(), 2);
            if (!hasData(body)) {
                body = null;
            }
        }
        if (body == null) {
            throw new RestException(RestError.of("rest_upload_no_data", "No data supplied.", 400));
        }
        // a header line sent empty is present all the same (an empty type; a disposition without a file name)
        List<String> types = request.headerValues("Content-Type");
        if (types.isEmpty()) {
            throw new RestException(RestError.of("rest_upload_no_content_type", "No Content-Type supplied.", 400));
        }
        List<String> dispositions = request.headerValues("Content-Disposition");
        if (dispositions.isEmpty()) {
            throw new RestException(
                    RestError.of("rest_upload_no_content_disposition", "No Content-Disposition supplied.", 400));
        }
        Optional<String> fileName = ContentDisposition.fileName(dispositions);
        if (fileName.isEmpty()) {
            throw new RestException(RestError.of(
                    "rest_upload_invalid_disposition",
                    "Invalid Content-Disposition supplied. Content-Disposition needs to be formatted as"
                            + " `attachment; filename=\"image.png\"` or similar.",
                    400));
        }
        MessageDigest digest = md5.isPresent() ? md5Digest() : null;
        Path spooled;
        try {
            spooled = media.spool(digest == null ? body : new DigestInputStream(body, digest));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        try {
            if (digest != null && !md5.get().equals(HexFormat.of().formatHex(digest.digest()))) {
                throw new RestException(hashMismatch());
            }
            return new Raw(spooled, Files.size(spooled), types.get(0), fileName.get());
        } catch (IOException failure) {
            delete(spooled, failure);
            throw new UncheckedIOException(failure);
        } catch (RuntimeException | Error failure) {
            delete(spooled, failure);
            throw failure;
        }
    }

    /**
     * Whether the body has data in PHP's sense: neither nothing nor exactly {@code 0}. The bytes read to tell are put
     * back.
     */
    private static boolean hasData(PushbackInputStream body) {
        try {
            int first = body.read();
            if (first < 0) {
                return false;
            }
            if (first != '0') {
                body.unread(first);
                return true;
            }
            int second = body.read();
            if (second < 0) {
                return false;
            }
            body.unread(second);
            body.unread(first);
            return true;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void delete(Path spooled, Throwable failure) {
        try {
            Files.deleteIfExists(spooled);
        } catch (IOException deleteFailure) {
            failure.addSuppressed(deleteFailure);
        }
    }

    // ---- Content-MD5

    /** The first {@code Content-MD5} value, trimmed as PHP trims; empty when the header is absent. */
    private static Optional<String> expectedMd5(RestRequest request) {
        List<String> values = request.headerValues("Content-MD5");
        return values.isEmpty() ? Optional.empty() : Optional.of(phpTrim(values.get(0)));
    }

    private static RestError hashMismatch() {
        return RestError.of("rest_upload_hash_mismatch", "Content hash did not match expected.", 412);
    }

    /** The lower-case hexadecimal MD5 of {@code file}'s bytes. */
    private static String md5(Path file) {
        MessageDigest digest = md5Digest();
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest md5Digest() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
    }

    private static String phpTrim(String value) {
        int from = 0;
        int to = value.length();
        while (from < to && phpBlank(value.charAt(from))) {
            from++;
        }
        while (to > from && phpBlank(value.charAt(to - 1))) {
            to--;
        }
        return value.substring(from, to);
    }

    private static boolean phpBlank(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u0000' || c == '\u000B';
    }

    // ---- bracketed file parts

    /**
     * The nested upload error codes PHP gives the file parts named {@code file[…]}, as WordPress prints them: a list
     * where the keys are 0, 1, … in order, an object otherwise; empty when no part is named so.
     */
    private static Optional<JsonNode> nestedCodes(List<UploadedFile> files) {
        ObjectNode tree = NODES.objectNode();
        for (UploadedFile file : files) {
            if (file.field().startsWith(FILE + "[")) {
                PhpQuery.add(tree, file.field(), Integer.toString(file.uploaded() ? UPLOAD_OK : NO_FILE));
            }
        }
        JsonNode nested = tree.get(FILE);
        return nested != null && nested.isObject() ? Optional.of(printed(nested)) : Optional.empty();
    }

    /** {@code node} as PHP's JSON encoder prints the array: codes as numbers, 0…n-1 keys as a list. */
    private static JsonNode printed(JsonNode node) {
        if (!node.isObject()) {
            return NODES.numberNode(Integer.parseInt(node.textValue()));
        }
        boolean list = true;
        int index = 0;
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if (!entry.getKey().equals(Integer.toString(index++))) {
                list = false;
                break;
            }
        }
        if (list) {
            ArrayNode array = NODES.arrayNode();
            node.forEach(value -> array.add(printed(value)));
            return array;
        }
        ObjectNode object = NODES.objectNode();
        node.properties().forEach(entry -> object.set(entry.getKey(), printed(entry.getValue())));
        return object;
    }
}
