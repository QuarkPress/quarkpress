package org.quarkpress.restwp;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * One file part of a multipart body, which Quarkus stores on disk (in {@code quarkus.http.body.uploads-directory}) for
 * the time of the request and deletes once the request ends: a handler that keeps the file copies or moves it.
 *
 * @param field the part's name, such as {@code file}
 * @param fileName the part's file name as Quarkus reads it from the part's {@code Content-Disposition}: not unescaped,
 *     with any directory part and NUL characters removed
 * @param contentType the part's {@code Content-Type} as sent, parameters kept; empty when the part has none
 * @param file where the part's bytes are stored
 * @param size the part's size in bytes
 */
public record UploadedFile(String field, String fileName, Optional<String> contentType, Path file, long size) {

    public UploadedFile {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(file, "file");
    }
}
