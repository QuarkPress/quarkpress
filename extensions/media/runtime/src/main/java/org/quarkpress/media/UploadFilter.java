package org.quarkpress.media;

import java.util.Optional;

/**
 * Checks an upload before it is stored, as WordPress's upload prefilter does: a refusal message stops the upload with
 * the error code {@code upload_error} and that message. Every bean of this type runs, in {@code @Priority} order,
 * highest first, until one refuses.
 */
@FunctionalInterface
public interface UploadFilter {

    /** The reason to refuse {@code upload}, or empty to let it through. */
    Optional<String> refuse(UploadCheck upload);
}
