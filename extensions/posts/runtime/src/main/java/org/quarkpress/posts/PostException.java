package org.quarkpress.posts;

import java.util.Objects;
import org.quarkpress.core.PostId;

/**
 * A post write or read refused for its input, with WordPress's error code: {@code invalid_post} (no such post on
 * this site), {@code empty_content} (title, content and excerpt all empty for a type that supports the three),
 * {@code invalid_date}, {@code db_insert_error} or {@code db_update_error} (a value too long for its column).
 */
public class PostException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final String detail;

    public PostException(String code, String message) {
        this(code, message, "");
    }

    public PostException(String code, String message, String detail) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
        this.detail = Objects.requireNonNull(detail, "detail");
    }

    /** The database's reason for a failed write, empty for every other error. */
    public String detail() {
        return detail;
    }

    /** WordPress's error code. */
    public String code() {
        return code;
    }

    public static PostException invalidPost(PostId post) {
        return new PostException("invalid_post", "Invalid post ID.");
    }

    public static PostException emptyContent() {
        return new PostException("empty_content", "Content, title, and excerpt are empty.");
    }

    public static PostException invalidDate() {
        return new PostException("invalid_date", "Invalid date.");
    }

    /** A value that does not fit its column, as WordPress's failed database write reports it. */
    public static PostException dbError(boolean update, String detail) {
        return update
                ? new PostException("db_update_error", "Could not update post in the database.", detail)
                : new PostException("db_insert_error", "Could not insert post into the database.", detail);
    }
}
