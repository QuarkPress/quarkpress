package org.quarkpress.revisions;

import java.util.Objects;

/**
 * A revision operation refused for its input, with WordPress's error code: {@code revisions_not_enabled} (the post's
 * limit is 0), {@code autosave_not_supported} (the post's type is not autosaved), {@code rest_post_invalid_id} (no
 * such revision on this site). A missing post is the posts module's {@code PostException} {@code invalid_post}.
 */
public class RevisionException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public RevisionException(String code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    /** WordPress's error code. */
    public String code() {
        return code;
    }

    public static RevisionException notEnabled() {
        return new RevisionException("revisions_not_enabled", "Revisions not enabled.");
    }

    public static RevisionException autosaveNotSupported(String type) {
        return new RevisionException(
                "autosave_not_supported", "Autosaves are not supported for posts of type " + type + ".");
    }

    public static RevisionException invalidRevision() {
        return new RevisionException("rest_post_invalid_id", "Invalid revision ID.");
    }
}
