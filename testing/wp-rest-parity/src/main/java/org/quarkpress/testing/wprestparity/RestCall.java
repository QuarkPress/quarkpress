package org.quarkpress.testing.wprestparity;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A request as a user names it: who sends it ({@code "anonymous"}, a {@link WordPressRestSite#USERS} entry, a user a
 * setup operation registered on the site, or {@code "invalid"}, a credential the server must refuse), the method, the
 * target (path and query, starting with {@code /}), extra headers, an optional body with its content type, and header
 * lines sent as given after the headers ({@code headerLines}: one line per value, in order, so a name can be sent
 * twice; empty unless the call was made by {@link #raw}).
 */
public record RestCall(
        String as,
        String method,
        String target,
        Map<String, String> headers,
        Optional<String> contentType,
        Optional<byte[]> body,
        Map<String, List<String>> headerLines) {

    public RestCall {
        Objects.requireNonNull(as, "as");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(headerLines, "headerLines");
        if (!target.startsWith("/")) {
            throw new IllegalArgumentException("a target starts with '/': " + target);
        }
        headers = Map.copyOf(headers);
        body = body.map(byte[]::clone);
        Map<String, List<String>> lines = new LinkedHashMap<>();
        headerLines.forEach((name, values) -> lines.put(name, List.copyOf(values)));
        headerLines = Collections.unmodifiableMap(lines);
    }

    /** A call without header lines (every factory but {@link #raw}). */
    public RestCall(
            String as,
            String method,
            String target,
            Map<String, String> headers,
            Optional<String> contentType,
            Optional<byte[]> body) {
        this(as, method, target, headers, contentType, body, Map.of());
    }

    /**
     * A POST whose body is {@code body} as is, with {@code contentType} (none when empty) and each value of
     * {@code headers} sent as a header line of its own, in the map's iteration order (two values of
     * {@code Content-Disposition} are two lines). The raw-body upload's form.
     */
    public static RestCall raw(
            String as, String target, Optional<String> contentType, Map<String, List<String>> headers, byte[] body) {
        Objects.requireNonNull(body, "body");
        return new RestCall(as, "POST", target, Map.of(), contentType, Optional.of(body), headers);
    }

    /** A GET of {@code target} with no extra headers and no body. */
    public static RestCall get(String as, String target) {
        return new RestCall(as, "GET", target, Map.of(), Optional.empty(), Optional.empty());
    }

    /** A request carrying {@code json} as an {@code application/json} body. */
    public static RestCall json(String as, String method, String target, String json) {
        return new RestCall(
                as,
                method,
                target,
                Map.of(),
                Optional.of("application/json"),
                Optional.of(json.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * The boundary {@link #multipart} uses unless the bytes or a field contain it; then {@code -1}, {@code -2}, ... is
     * appended until the boundary occurs nowhere in the parts.
     */
    public static final String MULTIPART_BOUNDARY = "qp-parity-boundary-7f3a9c1e";

    /**
     * A POST of a {@code multipart/form-data} body: one {@code file} part ({@code Content-Disposition: form-data;
     * name="file"; filename="<fileName>"}, {@code Content-Type: <declaredType>}) holding {@code bytes}, then one text
     * part per field, in the map's iteration order. The boundary is {@link #MULTIPART_BOUNDARY}, or the first suffixed
     * form of it that occurs in no part. Names are written as UTF-8, with {@code "}, CR and LF percent-encoded
     * ({@code %22}, {@code %0D}, {@code %0A}) as browsers send them; other characters are not escaped.
     */
    public static RestCall multipart(
            String as, String target, String fileName, String declaredType, byte[] bytes, Map<String, String> fields) {
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(declaredType, "declaredType");
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(fields, "fields");
        if (declaredType.indexOf('\r') >= 0 || declaredType.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("a content type has no line break: " + declaredType);
        }
        String boundary = MULTIPART_BOUNDARY;
        for (int n = 1; occursIn(boundary, bytes, fields); n++) {
            boundary = MULTIPART_BOUNDARY + "-" + n;
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream(bytes.length + 512);
        String dash = "--" + boundary + "\r\n";
        body.writeBytes(dash.getBytes(StandardCharsets.US_ASCII));
        body.writeBytes(("Content-Disposition: form-data; name=\"file\"; filename=\"" + escapeName(fileName) + "\"\r\n"
                        + "Content-Type: " + declaredType + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.writeBytes(bytes);
        body.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        for (Map.Entry<String, String> field : fields.entrySet()) {
            body.writeBytes(dash.getBytes(StandardCharsets.US_ASCII));
            body.writeBytes(("Content-Disposition: form-data; name=\"" + escapeName(field.getKey()) + "\"\r\n\r\n"
                            + field.getValue() + "\r\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        body.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return new RestCall(
                as,
                "POST",
                target,
                Map.of(),
                Optional.of("multipart/form-data; boundary=" + boundary),
                Optional.of(body.toByteArray()));
    }

    /** A part's name or file name with {@code "}, CR and LF percent-encoded, as browsers write them. */
    static String escapeName(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            switch (c) {
                case '"' -> out.append("%22");
                case '\r' -> out.append("%0D");
                case '\n' -> out.append("%0A");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean occursIn(String boundary, byte[] bytes, Map<String, String> fields) {
        byte[] needle = ("--" + boundary).getBytes(StandardCharsets.US_ASCII);
        if (indexOf(bytes, needle) >= 0) {
            return true;
        }
        for (Map.Entry<String, String> field : fields.entrySet()) {
            if (field.getKey().contains(boundary) || field.getValue().contains(boundary)) {
                return true;
            }
        }
        return false;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * This call with one more header, sent as given ({@code Cookie} and {@code X-WP-Nonce} of a cookie session
     * included); a header of the same name, compared without case, is replaced. A {@code Cookie} header makes the call
     * a cookie session: {@link RestClient} then sends no application password for {@link #as()}, whatever the cookie
     * holds. The call's {@link #headerLines} are kept.
     */
    public RestCall withHeader(String name, String value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        Map<String, String> all = new LinkedHashMap<>();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (!header.getKey().equalsIgnoreCase(name)) {
                all.put(header.getKey(), header.getValue());
            }
        }
        all.put(name, value);
        return new RestCall(as, method, target, all, contentType, body, headerLines);
    }
}
