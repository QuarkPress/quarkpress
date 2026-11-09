package org.quarkpress.restwp;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * One file part of a multipart body, which Quarkus stores on disk (in {@code quarkus.http.body.uploads-directory}) for
 * the time of the request and deletes once the request ends: a handler that keeps the file copies or moves it.
 *
 * <p>A part sent as a file without a usable file name (an empty one, or a directory alone), which is how a browser
 * sends a file field left empty, is a file part all the same, as PHP lists it among its uploaded files with "no file
 * was uploaded": Quarkus keeps none of its bytes, so its file name is empty, its size 0 and its file names nothing
 * stored ({@link #uploaded()} is false).
 *
 * @param field the part's name, such as {@code file}
 * @param fileName the part's file name as Quarkus reads it from the part's {@code Content-Disposition}: not unescaped,
 *     with any directory part and NUL characters removed; empty when no file was uploaded
 * @param contentType the part's {@code Content-Type} as sent, parameters kept; empty when the part has none
 * @param file where the part's bytes are stored; the empty path when no file was uploaded
 * @param size the part's size in bytes
 */
public record UploadedFile(String field, String fileName, Optional<String> contentType, Path file, long size) {

    private static final Path NOTHING = Path.of("");

    public UploadedFile {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(file, "file");
    }

    /** A part named {@code field} sent as a file without a usable file name: no file was uploaded. */
    public static UploadedFile notUploaded(String field, Optional<String> contentType) {
        return new UploadedFile(field, "", contentType, NOTHING, 0);
    }

    /** Whether the part carried a file: false for a part sent without a usable file name. */
    public boolean uploaded() {
        return !fileName.isEmpty();
    }
}
