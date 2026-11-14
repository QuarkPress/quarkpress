package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;
import org.quarkpress.core.PostId;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaException;
import org.quarkpress.media.Upload;
import org.quarkpress.media.runtime.UploadLimits;
import org.quarkpress.outbound.FetchException;
import org.quarkpress.outbound.FetchLimits;
import org.quarkpress.outbound.FetchResponse;
import org.quarkpress.outbound.SafeFetcher;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestException;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.RestResponse;
import org.quarkpress.restwp.runtime.PhpValues;
import org.quarkpress.restwp.runtime.RestJson;
import org.quarkpress.restwp.runtime.RestWpConfig;

/**
 * An upload from a URL, as WordPress's media route sideloads one: the file is named after the last segment of the
 * URL's path as it stands (percent-escapes kept, trailing slashes dropped as PHP's {@code basename} drops them, the
 * query and fragment ignored), which must exist and carry the
 * extension of an image type the caller may upload; the file is fetched through the outbound module's safe fetcher
 * (public addresses only, every redirect checked again, {@code quarkpress.rest-wp-v2.media-url-timeout}) with at
 * most one byte more than the site's upload cap, into the storage's temporary area; a fetch that fails answers
 * {@code http_request_failed} with its reason, a status other than 200 {@code http_404} with the reason phrase, the
 * status and the first kilobyte of the body, a {@code Content-MD5} that does not match {@code md5_mismatch}; the
 * network's limits are checked before the upload cap; then the file goes through the media module's sideload, which
 * names the attachment after the stored file and keeps an embedded caption as the description. The request's
 * {@code post}, {@code generate_sub_sizes} and {@code convert_format} count; its title, caption, description,
 * alternative text and post fields are ignored, as WordPress ignores them. The answer is 201 with {@code Location}
 * and no upload header.
 */
@ApplicationScoped
class UrlUpload {

    private static final Logger LOG = Logger.getLogger(UrlUpload.class);

    private static final int ERROR_BODY_BYTES = 1024;

    private static final int MAX_REDIRECTS = 5;

    private static final String NO_FILE_NAME = "Could not determine a filename from the provided URL.";

    private static final String NOT_AN_IMAGE = "The provided URL does not point to a supported image file.";

    @Inject
    SafeFetcher fetcher;

    @Inject
    RestWpConfig config;

    @Inject
    Media library;

    @Inject
    UploadLimits limits;

    @Inject
    MediaWriter writer;

    /** A fetched file: where it was spooled and its size. */
    private record Fetched(Path spooled, long size) {}

    /**
     * Fetches {@code url} and stores it as the request's upload; see the class description. Must be called with no
     * transaction active, after the create permission and the parent's type were checked.
     */
    RestResponse create(PostRoutes routes, RestRequest request, String url) {
        URI uri = parse(url);
        String name = fileName(uri);
        if (!isImage(name, library.allowedTypes())) {
            throw new RestException(RestError.of("rest_invalid_url", NOT_AN_IMAGE, 400));
        }
        long cap = library.maxUploadSize();
        Fetched fetched = fetch(uri, cap);
        try {
            checkSize(fetched.size(), cap);
            Upload.Builder upload = Upload.builder(name, fetched.spooled())
                    .sideloaded()
                    .imageTypeCheck(false)
                    .generateSubSizes(flag(request, "generate_sub_sizes"))
                    .convertFormat(flag(request, "convert_format"));
            request.param("post")
                    .map(PhpValues::toLong)
                    .filter(parent -> parent > 0)
                    .ifPresent(parent -> upload.parent(PostId.of(parent)));
            return writer.sideload(routes, request, upload.build());
        } finally {
            try {
                Files.deleteIfExists(fetched.spooled());
            } catch (IOException failure) {
                LOG.warnf(failure, "the fetched file %s could not be deleted", fetched.spooled());
            }
        }
    }

    /**
     * The sanitized URL parsed once, for the file name and the fetch.
     *
     * @throws RestException 400 {@code rest_invalid_url} when it does not parse: such a value has no path, so no file
     *     name, as WordPress's {@code wp_parse_url} gives none
     */
    private static URI parse(String url) {
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            throw new RestException(RestError.of("rest_invalid_url", NO_FILE_NAME, 400));
        }
    }

    /**
     * The last segment of the URL's path as it stands, without the query and the fragment, read as PHP's
     * {@code basename} reads it: trailing slashes are dropped first, so {@code /dir/} names {@code dir}.
     *
     * @throws RestException 400 {@code rest_invalid_url} when the path is empty or only slashes
     */
    static String fileName(URI uri) {
        String path = uri.getRawPath();
        if (path == null) {
            path = "";
        }
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') {
            end--;
        }
        path = path.substring(0, end);
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.isEmpty()) {
            throw new RestException(RestError.of("rest_invalid_url", NO_FILE_NAME, 400));
        }
        return name;
    }

    /**
     * Whether {@code name} ends in an extension of an {@code image/} type among {@code allowed} (extension patterns
     * such as {@code jpg|jpeg|jpe} to their types), compared ignoring ASCII case.
     */
    static boolean isImage(String name, List<Map.Entry<String, String>> allowed) {
        for (Map.Entry<String, String> entry : allowed) {
            if (!entry.getValue().startsWith("image/")) {
                continue;
            }
            String pattern = entry.getKey();
            int start = 0;
            while (start <= pattern.length()) {
                int bar = pattern.indexOf('|', start);
                String extension = pattern.substring(start, bar < 0 ? pattern.length() : bar);
                if (!extension.isEmpty() && endsWithExtension(name, extension)) {
                    return true;
                }
                if (bar < 0) {
                    break;
                }
                start = bar + 1;
            }
        }
        return false;
    }

    private static boolean endsWithExtension(String name, String extension) {
        int dot = name.length() - extension.length() - 1;
        return dot >= 0
                && name.charAt(dot) == '.'
                && name.regionMatches(true, dot + 1, extension, 0, extension.length());
    }

    /**
     * Fetches {@code uri} into the storage's temporary area, reading at most {@code cap + 1} bytes (the configured
     * outbound limit when the cap is not positive); a body cut there is checked as a file one byte longer than the
     * bound, and refused as the cut when no size check refuses it.
     */
    private Fetched fetch(URI uri, long cap) {
        long maxBytes = cap > 0 ? cap + 1 : fetcher.defaults().maxBytes();
        FetchLimits limits = new FetchLimits(config.mediaUrlTimeout(), MAX_REDIRECTS, maxBytes);
        try (FetchResponse response = fetcher.get(uri, limits)) {
            if (response.status() != 200) {
                throw new RestException(notFound(response));
            }
            // a header that is not a well-formed MD5 is ignored, as WordPress ignores it
            Optional<String> expectedMd5 =
                    response.header("Content-MD5").stream().findFirst().flatMap(UrlUpload::expectedHex);
            MessageDigest digest = expectedMd5.isPresent() ? md5Digest() : null;
            Path spooled;
            try {
                spooled = library.spool(
                        digest == null ? response.body() : new DigestInputStream(response.body(), digest));
            } catch (FetchException cut) {
                if (cut.kind() == FetchException.Kind.TOO_LARGE) {
                    // the body was cut past the bound: under a positive cap WordPress's download keeps cap + 1
                    // bytes, without one the file is at least a byte longer than the bound; the size checks refuse
                    // that length as WordPress refuses the file (the network's limits when there is no cap)
                    checkSize(cap > 0 ? cap + 1 : maxBytes + 1, cap);
                }
                throw cut;
            }
            try {
                if (digest != null) {
                    String actual = HexFormat.of().formatHex(digest.digest());
                    String expected = expectedMd5.get();
                    if (!actual.equals(expected)) {
                        throw new RestException(RestError.noStatus(
                                "md5_mismatch",
                                "The checksum of the file (" + actual + ") does not match the expected checksum value ("
                                        + expected + ")."));
                    }
                }
                return new Fetched(spooled, Files.size(spooled));
            } catch (RuntimeException | Error | IOException failure) {
                try {
                    Files.deleteIfExists(spooled);
                } catch (IOException deleteFailure) {
                    failure.addSuppressed(deleteFailure);
                }
                throw failure;
            }
        } catch (FetchException failed) {
            throw new RestException(RestError.noStatus("http_request_failed", String.valueOf(failed.getMessage())));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /**
     * WordPress's error for a status other than 200: {@code http_404} whatever the status, the reason phrase as the
     * message, and the status with the first kilobyte of the body as data (no status key, so the answer is 500).
     */
    private static RestError notFound(FetchResponse response) throws IOException {
        byte[] head;
        try (InputStream body = response.body()) {
            head = body.readNBytes(ERROR_BODY_BYTES);
        } catch (FetchException ignored) {
            head = new byte[0];
        }
        ObjectNode data = RestJson.NODES.objectNode();
        data.put("code", response.status());
        data.put("body", new String(head, StandardCharsets.UTF_8));
        return new RestError("http_404", response.reason(), data, List.of(), List.of());
    }

    /**
     * WordPress's checks of the fetched file's size, in its order: the network's limits (their own messages), then
     * the site's upload cap (thousands separated).
     */
    private void checkSize(long size, long cap) {
        Optional<MediaException> overNetworkLimit = limits.forSite(size);
        if (overNetworkLimit.isPresent()) {
            throw new RestException(RestError.of(
                    overNetworkLimit.get().code(), overNetworkLimit.get().getMessage(), 400));
        }
        if (cap > 0 && size > cap) {
            throw new RestException(RestError.of(
                    "rest_upload_file_too_big",
                    "This file is too big. Files must be less than " + UploadLimits.numberFormat((double) cap / 1024)
                            + " KB in size.",
                    400));
        }
    }

    /**
     * The expected MD5 of a {@code Content-MD5} header as lower-case hexadecimal, as WordPress's
     * {@code verify_file_md5} reads it: a 32-character value as given, a 24-character one decoded from base64; empty
     * for any other length, or a 24-character value that is no base64, which WordPress does not check.
     */
    private static Optional<String> expectedHex(String header) {
        String value = PhpValues.trim(header);
        if (value.length() == 32) {
            return Optional.of(value.toLowerCase(Locale.ROOT));
        }
        if (value.length() == 24) {
            try {
                return Optional.of(HexFormat.of().formatHex(Base64.getDecoder().decode(value)));
            } catch (IllegalArgumentException notBase64) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static MessageDigest md5Digest() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
    }

    /** A boolean argument, true unless the request says otherwise. */
    private static boolean flag(RestRequest request, String name) {
        return request.param(name).map(PhpValues::toBoolean).orElse(true);
    }
}
