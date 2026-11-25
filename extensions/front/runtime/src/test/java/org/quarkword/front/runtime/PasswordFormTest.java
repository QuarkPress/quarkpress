package org.quarkword.front.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkword.core.PostId;
import org.quarkword.core.SiteId;
import org.quarkword.posts.PostSnapshot;

/**
 * The classic-theme password form of WordPress 7.1.3's {@code get_the_password_form}, as postpass.out in the slice
 * folder records it for post 9 with {@code siteurl} and {@code home} {@code http://wp.test} (plain permalinks).
 */
class PasswordFormTest {

    private static final String LOGIN = "http://wp.test/wp-login.php";
    private static final String PERMALINK = "http://wp.test/?p=9";

    static PostSnapshot post(long id, String password) {
        Instant at = Instant.parse("2026-06-01T10:00:00Z");
        return new PostSnapshot(
                PostId.of(id),
                SiteId.of(1),
                "post",
                "publish",
                "secret-recipe",
                "Secret recipe",
                "SECRET CONTENT",
                "SECRET EXCERPT",
                Optional.empty(),
                Optional.empty(),
                0,
                Optional.of(at),
                at,
                at,
                "open",
                "open",
                password,
                false,
                "",
                Map.of(),
                1);
    }

    @Test
    void theFormAsWordPressWritesIt() {
        assertThat(PasswordForm.html(post(9, "s3"), LOGIN, PERMALINK, false))
                .isEqualTo("<form action=\"http://wp.test/wp-login.php?action=postpass\" class=\"post-password-form\""
                        + " method=\"post\"><input type=\"hidden\" name=\"redirect_to\" value=\"http://wp.test/?p=9\""
                        + " />\n\t<p>This content is password-protected. To view it, please enter the password"
                        + " below.</p>\n\t<p><label for=\"pwbox-9\">Password: <input name=\"post_password\""
                        + " id=\"pwbox-9\" type=\"password\" spellcheck=\"false\" required size=\"20\" /></label>"
                        + " <input type=\"submit\" name=\"Submit\" value=\"Enter\" /></p></form>\n\t");
    }

    @Test
    void theInvalidPasswordForm() {
        assertThat(PasswordForm.html(post(9, "s3"), LOGIN, PERMALINK, true))
                .isEqualTo("<form action=\"http://wp.test/wp-login.php?action=postpass\" class=\"post-password-form"
                        + " password-form-error\" method=\"post\"><input type=\"hidden\" name=\"redirect_to\""
                        + " value=\"http://wp.test/?p=9\" /><div class=\"post-password-form-invalid-password\""
                        + " role=\"alert\"><p id=\"error-pwbox-9\">Invalid password.</p></div>\n\t<p>This content is"
                        + " password-protected. To view it, please enter the password below.</p>\n\t<p><label"
                        + " for=\"pwbox-9\">Password: <input name=\"post_password\" id=\"pwbox-9\" type=\"password\""
                        + " spellcheck=\"false\" required size=\"20\" aria-describedby=\"error-pwbox-9\" /></label>"
                        + " <input type=\"submit\" name=\"Submit\" value=\"Enter\" /></p></form>\n\t");
    }

    @Test
    void theAddressesAreAttributeEscapedAndThePostsTextsNeverAppear() {
        String html = PasswordForm.html(
                post(9, "s3\"><script>"), "http://wp.test/a\"b<c>/wp-login.php", "http://wp.test/?p=9&x=\"'<>", false);
        assertThat(html)
                .startsWith("<form action=\"http://wp.test/a&quot;b&lt;c&gt;/wp-login.php?action=postpass\"")
                .contains("value=\"http://wp.test/?p=9&amp;x=&quot;&#039;&lt;&gt;\"")
                .doesNotContain("<script>")
                .doesNotContain("SECRET")
                .doesNotContain("s3");
    }

    @Test
    void theProtectedExcerpt() {
        assertThat(PasswordForm.PROTECTED_EXCERPT).isEqualTo("There is no excerpt because this is a protected post.");
    }
}
