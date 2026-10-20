package org.quarkpress.posts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/**
 * Post passwords. WordPress stores them as given (no hashing, no trimming) and compares them as plain text; a post
 * without one needs none. Rendering a protected post (the form, the cookie) belongs to the HTTP layers.
 */
public final class PostPasswords {

    private PostPasswords() {}

    /** Whether reading the post's content needs a password; as in PHP, "0" counts as none. */
    public static boolean required(PostSnapshot post) {
        Objects.requireNonNull(post, "post");
        return post.passwordProtected() && !post.password().equals("0");
    }

    /** Whether {@code candidate} is exactly the post's password; false for a post without one. Constant time. */
    public static boolean matches(PostSnapshot post, String candidate) {
        Objects.requireNonNull(post, "post");
        Objects.requireNonNull(candidate, "candidate");
        if (!required(post)) {
            return false;
        }
        return MessageDigest.isEqual(
                post.password().getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8));
    }
}
