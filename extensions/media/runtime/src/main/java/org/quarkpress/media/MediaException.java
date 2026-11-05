package org.quarkpress.media;

import java.util.Objects;

/**
 * An upload or media change refused, with WordPress's error code and message: {@code upload_error} for the refusals
 * of the upload itself (its type, an empty file, a folder or file that cannot be written, an upload filter, the
 * configured size limit), and the REST codes WordPress answers before it receives a file
 * ({@code rest_upload_image_type_not_supported}, {@code rest_invalid_param}, {@code rest_cannot_edit}).
 */
public class MediaException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /** WordPress's message for a file whose type the user may not upload. */
    public static final String TYPE_NOT_ALLOWED = "Sorry, you are not allowed to upload this file type.";

    private final String code;

    public MediaException(String code, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.code = Objects.requireNonNull(code, "code");
    }

    /** WordPress's error code. */
    public String code() {
        return code;
    }

    /** A refusal of the upload itself, with {@code message}. */
    public static MediaException upload(String message) {
        return new MediaException("upload_error", message);
    }
}
