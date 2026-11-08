package org.quarkpress.media.storage.fs.runtime;

import io.smallrye.common.annotation.Identifier;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.media.StoredKey;
import org.quarkpress.media.runtime.DefaultMimeTypes;

/**
 * Serves the stored files under the uploads path ({@code /wp-content/uploads} by default), as WordPress's web server
 * serves its uploads folder: {@code GET} and {@code HEAD} only, with the content type of the file's extension and
 * {@code X-Content-Type-Options: nosniff}, never a folder listing. A single byte range is served ({@code 206}, or
 * {@code 416} when no byte satisfies it), and {@code If-Modified-Since} against the file's {@code Last-Modified} gives
 * {@code 304}. The path may follow a site path of up to
 * {@value #SITE_SEGMENTS} segments ({@code /site3/wp-content/uploads/...}). The key is taken from the raw request path
 * and percent-decoded once; a key that is not valid, has a segment starting with {@code .}, or names no file gets a
 * 404.
 *
 * <p>Registered only when the filesystem backend is the one in use and no base URL publishes the files elsewhere.
 */
public class UploadsRoute {

    /** The most segments of a site path in front of the uploads path. */
    static final int SITE_SEGMENTS = 4;

    static final String NOSNIFF = "nosniff";

    private static final String OCTET_STREAM = "application/octet-stream";

    /** The most bytes read from a file at a time while sending it. */
    private static final int CHUNK = 64 * 1024;

    private static final Map<String, String> TYPES = types();

    @Inject
    FsConfig config;

    @Inject
    @Identifier("fs")
    FsMediaStorage storage;

    void register(@Observes Router router) {
        if (config.baseUrl().isPresent()) {
            return;
        }
        List<String> prefix = segments(config.path());
        router.route().method(HttpMethod.GET).method(HttpMethod.HEAD).handler(context -> handle(context, prefix));
    }

    private void handle(RoutingContext context, List<String> prefix) {
        Optional<String> rawKey = rawKey(context.request().path(), prefix);
        if (rawKey.isEmpty()) {
            context.next();
            return;
        }
        Optional<String> key = servedKey(rawKey.get());
        if (key.isEmpty()) {
            notFound(context);
            return;
        }
        context.vertx()
                .executeBlocking(() -> storage.servable(key.get()), false)
                .onComplete(found -> {
                    if (found.failed() || found.result().isEmpty()) {
                        notFound(context);
                    } else {
                        FsMediaStorage.Served file = found.result().get();
                        try {
                            send(context, key.get(), file);
                        } catch (RuntimeException failure) {
                            close(file);
                            throw failure;
                        }
                    }
                });
    }

    /**
     * Answers with the opened file, which is read from its channel (never reopened by name) and closed once sent or
     * once the answer needs none of it.
     */
    private static void send(RoutingContext context, String key, FsMediaStorage.Served file) {
        HttpServerResponse response = context.response();
        response.putHeader(HttpHeaders.CONTENT_TYPE, contentType(key));
        response.putHeader("X-Content-Type-Options", NOSNIFF);
        response.putHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        Instant modified = file.lastModified().truncatedTo(ChronoUnit.SECONDS);
        response.putHeader(HttpHeaders.LAST_MODIFIED, httpDate(modified));
        Optional<Instant> since = parseHttpDate(context.request().getHeader(HttpHeaders.IF_MODIFIED_SINCE));
        if (notModified(modified, since, Instant.now())) {
            close(file);
            response.setStatusCode(304).end();
            return;
        }
        long size = file.size();
        Range range = rangeIfCurrent(
                context.request().getHeader("Range"), context.request().getHeader("If-Range"), modified, size);
        long offset = 0;
        long length = size;
        if (range == Range.UNSATISFIABLE) {
            close(file);
            response.putHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + size);
            response.setStatusCode(416).end();
            return;
        } else if (range != null) {
            offset = range.first();
            length = range.last() - range.first() + 1;
            response.putHeader(HttpHeaders.CONTENT_RANGE, "bytes " + range.first() + "-" + range.last() + "/" + size);
            response.setStatusCode(206);
        }
        response.putHeader(HttpHeaders.CONTENT_LENGTH, Long.toString(length));
        if (context.request().method() == HttpMethod.HEAD || length == 0) {
            close(file);
            response.end();
            return;
        }
        new Transfer(context, file, offset, length).next();
    }

    /**
     * Sends {@code length} bytes of an opened file from {@code offset}: each chunk is read on a worker thread and
     * written when the connection takes more, and the file is closed at the end, on a failure or when the client
     * goes away.
     */
    private static final class Transfer {

        private final RoutingContext context;
        private final FsMediaStorage.Served file;
        private long position;
        private long remaining;
        private boolean closed;

        Transfer(RoutingContext context, FsMediaStorage.Served file, long offset, long length) {
            this.context = context;
            this.file = file;
            this.position = offset;
            this.remaining = length;
            // a close handler set now misses a connection that closed while the file was being opened, so the
            // response is also asked whether it is closed before each chunk is read and once it has been read
            context.response().closeHandler(gone -> finish());
            context.response().exceptionHandler(failure -> finish());
        }

        void next() {
            if (stopped()) {
                return;
            }
            int count = (int) Math.min(CHUNK, remaining);
            context.vertx().executeBlocking(() -> read(count), true).onComplete(read -> {
                if (stopped()) {
                    return;
                }
                if (read.failed()) {
                    fail();
                    return;
                }
                HttpServerResponse response = context.response();
                remaining -= count;
                position += count;
                if (remaining == 0) {
                    finish();
                    response.end(read.result());
                    return;
                }
                response.write(read.result());
                if (response.writeQueueFull()) {
                    response.drainHandler(drained -> next());
                } else {
                    next();
                }
            });
        }

        private Buffer read(int count) throws IOException {
            ByteBuffer chunk = ByteBuffer.allocate(count);
            SeekableByteChannel channel = file.channel();
            channel.position(position);
            while (chunk.hasRemaining()) {
                if (channel.read(chunk) < 0) {
                    throw new EOFException("the file is shorter than when it was opened");
                }
            }
            return Buffer.buffer(chunk.array());
        }

        /** Whether the transfer is over: finished, or its response closed because the client went away. */
        private boolean stopped() {
            if (!closed && context.response().closed()) {
                finish();
            }
            return closed;
        }

        private void fail() {
            finish();
            if (!context.response().headWritten()) {
                notFound(context);
            } else {
                context.request().connection().close();
            }
        }

        private void finish() {
            if (!closed) {
                closed = true;
                close(file);
            }
        }
    }

    private static void close(FsMediaStorage.Served file) {
        try {
            file.close();
        } catch (IOException ignored) {
            // nothing was written through it
        }
    }

    /** A byte range of a file, both ends included. */
    record Range(long first, long last) {

        /** A range request no byte of the file satisfies. */
        static final Range UNSATISFIABLE = new Range(-1, -1);
    }

    /**
     * The one byte range a {@code Range} header asks of a file of {@code size} bytes: {@code bytes=a-b},
     * {@code bytes=a-} or {@code bytes=-n} (the last n bytes); {@link Range#UNSATISFIABLE} when it starts beyond the
     * file or asks for no byte; null (the whole file is sent) when there is no header, it is malformed, or it asks
     * for several ranges.
     */
    static Range range(String header, long size) {
        if (header == null) {
            return null;
        }
        String value = header.trim();
        if (!value.regionMatches(true, 0, "bytes=", 0, "bytes=".length()) || value.indexOf(',') >= 0) {
            return null;
        }
        String spec = value.substring("bytes=".length()).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        long first = digits(spec.substring(0, dash).trim());
        long last = digits(spec.substring(dash + 1).trim());
        boolean noFirst = dash == 0 || spec.substring(0, dash).isBlank();
        boolean noLast = spec.substring(dash + 1).isBlank();
        if (noFirst) {
            if (noLast || last < 0) {
                return null;
            }
            if (last == 0 || size == 0) {
                return Range.UNSATISFIABLE;
            }
            return new Range(Math.max(0, size - last), size - 1);
        }
        if (first < 0 || (!noLast && (last < 0 || last < first))) {
            return null;
        }
        if (first >= size) {
            return Range.UNSATISFIABLE;
        }
        return new Range(first, noLast ? size - 1 : Math.min(last, size - 1));
    }

    /**
     * The range to serve, as {@link #range}, unless an {@code If-Range} header is present and is not an HTTP date
     * equal to the file's {@code modified} time (to the second): then null, and the whole file is sent. An entity tag
     * never matches, since no tags are sent.
     */
    static Range rangeIfCurrent(String header, String ifRange, Instant modified, long size) {
        if (ifRange != null) {
            Optional<Instant> date = parseHttpDate(ifRange);
            if (date.isEmpty() || !date.get().equals(modified.truncatedTo(ChronoUnit.SECONDS))) {
                return null;
            }
        }
        return range(header, size);
    }

    /**
     * Whether a file last modified at {@code modified} (whole seconds) is unchanged since {@code since}: a date later
     * than {@code now}, the server's clock, is no valid condition and is ignored.
     */
    static boolean notModified(Instant modified, Optional<Instant> since, Instant now) {
        return since.isPresent() && !since.get().isAfter(now) && !modified.isAfter(since.get());
    }

    /** A non-negative decimal number of at most 18 digits; -1 when {@code text} is anything else. */
    private static long digits(String text) {
        if (text.isEmpty() || text.length() > 18) {
            return -1;
        }
        long value = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            value = value * 10 + (c - '0');
        }
        return value;
    }

    /** HTTP's fixed date form, {@code Thu, 05 Nov 2026 12:00:00 GMT}. */
    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH);

    static String httpDate(Instant instant) {
        return HTTP_DATE.format(instant.atOffset(ZoneOffset.UTC));
    }

    /** An HTTP date of the RFC 1123 form; empty when absent or in another form (the condition is then ignored). */
    static Optional<Instant> parseHttpDate(String header) {
        if (header == null || header.length() > 64) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(header.trim())));
        } catch (DateTimeException malformed) {
            return Optional.empty();
        }
    }

    private static void notFound(RoutingContext context) {
        HttpServerResponse response = context.response();
        if (!response.ended()) {
            response.setStatusCode(404).end();
        }
    }

    /**
     * The raw key of a request path: what follows the uploads path's segments, which come first or after a site path
     * of up to {@value #SITE_SEGMENTS} segments; empty when the path is not below the uploads path.
     */
    static Optional<String> rawKey(String requestPath, List<String> prefix) {
        if (requestPath == null || requestPath.isEmpty() || requestPath.charAt(0) != '/') {
            return Optional.empty();
        }
        List<Integer> starts = new ArrayList<>();
        List<String> segments = new ArrayList<>();
        int start = 1;
        while (start <= requestPath.length()) {
            int end = requestPath.indexOf('/', start);
            if (end < 0) {
                end = requestPath.length();
            }
            starts.add(start);
            segments.add(requestPath.substring(start, end));
            start = end + 1;
            if (segments.size() > SITE_SEGMENTS + prefix.size()) {
                break;
            }
        }
        for (int offset = 0; offset <= SITE_SEGMENTS; offset++) {
            if (offset + prefix.size() >= segments.size()) {
                return Optional.empty();
            }
            if (segments.subList(offset, offset + prefix.size()).equals(prefix)) {
                return Optional.of(requestPath.substring(starts.get(offset + prefix.size())));
            }
        }
        return Optional.empty();
    }

    /**
     * The key a raw key names, decoded once; empty when it is malformed, invalid or has a segment starting with a
     * dot.
     */
    static Optional<String> servedKey(String rawKey) {
        Optional<String> decoded = percentDecoded(rawKey);
        if (decoded.isEmpty()) {
            return Optional.empty();
        }
        String key = decoded.get();
        try {
            StoredKey.require(key);
        } catch (IllegalArgumentException refused) {
            return Optional.empty();
        }
        if (key.charAt(0) == '.' || key.contains("/.")) {
            return Optional.empty();
        }
        return decoded;
    }

    /** {@code text} with each {@code %XX} replaced by its byte, read as UTF-8; empty when malformed. */
    static Optional<String> percentDecoded(String text) {
        ByteBuffer bytes = ByteBuffer.allocate(text.length() * 3);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '%') {
                if (i + 2 >= text.length()) {
                    return Optional.empty();
                }
                int high = Character.digit(text.charAt(i + 1), 16);
                int low = Character.digit(text.charAt(i + 2), 16);
                if (high < 0 || low < 0) {
                    return Optional.empty();
                }
                bytes.put((byte) (high * 16 + low));
                i += 2;
            } else if (c < 0x80) {
                bytes.put((byte) c);
            } else {
                byte[] encoded = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                if (Character.isHighSurrogate(c) && i + 1 < text.length()) {
                    encoded = text.substring(i, i + 2).getBytes(StandardCharsets.UTF_8);
                    i++;
                }
                bytes.put(encoded);
            }
        }
        bytes.flip();
        try {
            CharBuffer chars = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(bytes);
            return Optional.of(chars.toString());
        } catch (CharacterCodingException malformed) {
            return Optional.empty();
        }
    }

    /** The content type of a key: that of its extension in WordPress's upload types, else a byte stream. */
    static String contentType(String key) {
        int slash = key.lastIndexOf('/');
        int dot = key.lastIndexOf('.');
        if (dot <= slash + 1 || dot == key.length() - 1) {
            return OCTET_STREAM;
        }
        String type = TYPES.get(lowerAscii(key.substring(dot + 1)));
        return type != null ? type : OCTET_STREAM;
    }

    /**
     * Refuses an uploads path with no segment ({@code /}, or empty): the route would then take every request of the
     * application.
     */
    static void requireServablePath(String path) {
        if (segments(path).isEmpty()) {
            throw new IllegalStateException(
                    "quarkpress.media.fs.path must name a path below the site's root, such as /wp-content/uploads: "
                            + path);
        }
    }

    /** The non-empty {@code /}-separated segments of a configured path. */
    static List<String> segments(String path) {
        List<String> segments = new ArrayList<>();
        int start = 0;
        while (start <= path.length()) {
            int end = path.indexOf('/', start);
            if (end < 0) {
                end = path.length();
            }
            if (end > start) {
                segments.add(path.substring(start, end));
            }
            start = end + 1;
        }
        return List.copyOf(segments);
    }

    private static Map<String, String> types() {
        Map<String, String> types = new HashMap<>();
        for (Map.Entry<String, String> entry : DefaultMimeTypes.PATTERNS) {
            String pattern = entry.getKey();
            int start = 0;
            while (start <= pattern.length()) {
                int end = pattern.indexOf('|', start);
                if (end < 0) {
                    end = pattern.length();
                }
                if (end > start) {
                    types.putIfAbsent(lowerAscii(pattern.substring(start, end)), entry.getValue());
                }
                start = end + 1;
            }
        }
        return Map.copyOf(types);
    }

    private static String lowerAscii(String text) {
        StringBuilder lower = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            lower.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        return lower.toString();
    }
}
