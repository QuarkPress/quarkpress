package org.quarkword.front.runtime;

import java.util.Objects;
import org.quarkword.posts.PostSnapshot;

/**
 * WordPress's password form for a protected post (the classic-theme markup of {@code get_the_password_form}), shown in
 * place of the content until the visitor's post-password cookie holds the post's password. It posts the password to
 * {@code wp-login.php?action=postpass} with the post's permalink as {@code redirect_to}; after a wrong password (the
 * request came from the permalink and carries the cookie) it shows WordPress's invalid-password notice. The addresses
 * are attribute-escaped; nothing of the post but its id is written.
 */
public final class PasswordForm {

    /** The excerpt WordPress gives a post whose password the visitor has not entered. */
    public static final String PROTECTED_EXCERPT = "There is no excerpt because this is a protected post.";

    private PasswordForm() {}

    /**
     * The form for {@code post}: {@code loginUrl} is the site's {@code wp-login.php} address in the login scheme,
     * {@code permalink} the post's; {@code invalid} adds the invalid-password notice.
     */
    public static String html(PostSnapshot post, String loginUrl, String permalink, boolean invalid) {
        Objects.requireNonNull(post, "post");
        Objects.requireNonNull(loginUrl, "loginUrl");
        Objects.requireNonNull(permalink, "permalink");
        String field = "pwbox-" + post.id().value();
        StringBuilder html = new StringBuilder(640);
        html.append("<form action=\"")
                .append(FallbackPage.escape(loginUrl + "?action=postpass"))
                .append("\" class=\"post-password-form")
                .append(invalid ? " password-form-error" : "")
                .append("\" method=\"post\">")
                .append("<input type=\"hidden\" name=\"redirect_to\" value=\"")
                .append(FallbackPage.escape(permalink))
                .append("\" />");
        if (invalid) {
            html.append("<div class=\"post-password-form-invalid-password\" role=\"alert\"><p id=\"error-")
                    .append(field)
                    .append("\">Invalid password.</p></div>");
        }
        html.append("\n\t<p>This content is password-protected. To view it, please enter the password below.</p>")
                .append("\n\t<p><label for=\"")
                .append(field)
                .append("\">Password: <input name=\"post_password\" id=\"")
                .append(field)
                .append("\" type=\"password\" spellcheck=\"false\" required size=\"20\"");
        if (invalid) {
            html.append(" aria-describedby=\"error-").append(field).append('"');
        }
        html.append(" /></label> <input type=\"submit\" name=\"Submit\" value=\"Enter\" /></p></form>\n\t");
        return html.toString();
    }
}
