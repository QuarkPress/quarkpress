package org.quarkword.front.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@code wp_validate_redirect} and {@code wp_sanitize_redirect} as WordPress 7.1.3 answers them (redirects.out in the
 * slice folder: home {@code http://wp.test}, request {@code /wp-login.php?action=postpass}, fallback {@code F}).
 */
class RedirectUrlsTest {

    private static String validate(String url) {
        return RedirectUrls.validate(url, "wp.test", "/wp-login.php?action=postpass", "F");
    }

    @Test
    void validate() {
        assertThat(validate("//evil.test/x")).isEqualTo("F");
        assertThat(validate("javascript:alert(1)")).isEqualTo("F");
        // a path from the root is kept as it is (WordPress does not make it absolute)
        assertThat(validate("/2025/")).isEqualTo("/2025/");
        assertThat(validate("https://wp.test/x")).isEqualTo("https://wp.test/x");
        assertThat(validate("http://evil.test/")).isEqualTo("F");
        assertThat(validate("http://WP.TEST/x")).isEqualTo("F");
        assertThat(validate("http://wp.test:8080/x")).isEqualTo("http://wp.test:8080/x");
        assertThat(validate("relative/path")).isEqualTo("/relative/path");
        assertThat(validate("  https://wp.test/y ")).isEqualTo("https://wp.test/y");
        assertThat(validate("https:wp.test")).isEqualTo("F");
        assertThat(validate("http://user@wp.test/")).isEqualTo("http://user@wp.test/");
        assertThat(validate("http://u:p@wp.test/")).isEqualTo("http://u:p@wp.test/");
        assertThat(validate("data:text/html,x")).isEqualTo("F");
        assertThat(validate("http://wp.test/?a=http://evil.test")).isEqualTo("http://wp.test/?a=http://evil.test");
        assertThat(validate("HTTP://wp.test/x")).isEqualTo("F");
        assertThat(validate("http:///wp.test/x")).isEqualTo("F");
        assertThat(validate("?x=1")).isEqualTo("?x=1");
        assertThat(validate("#frag")).isEqualTo("#frag");
        assertThat(validate("")).isEqualTo("");
        // PHP's empty(): a path "0" is not made absolute (postpass.out), "00" is
        assertThat(validate("0")).isEqualTo("0");
        assertThat(validate("00")).isEqualTo("/00");
        assertThat(validate("http://wp.test@evil.test/")).isEqualTo("F");
        assertThat(validate("wp.test:80/x")).isEqualTo("wp.test:80/x");
        assertThat(validate("http://wp.test#@evil.test")).isEqualTo("http://wp.test#@evil.test");
        assertThat(RedirectUrls.validate("relative/path", "wp.test", "/a/b/c?x=1", "F"))
                .isEqualTo("/a/b/relative/path");
        assertThat(RedirectUrls.validate("relative/path", "wp.test", "F")).isEqualTo("/relative/path");
    }

    @Test
    void sanitize() {
        assertThat(RedirectUrls.sanitize("http://wp.test/a\r\nb")).isEqualTo("http://wp.test/ab");
        assertThat(RedirectUrls.sanitize("http://wp.test/a%0d%0aLocation:x")).isEqualTo("http://wp.test/aLocation:x");
        assertThat(RedirectUrls.sanitize("http://wp.test/%0%0dd")).isEqualTo("http://wp.test/");
        assertThat(RedirectUrls.sanitize("http://wp.test/кот/")).isEqualTo("http://wp.test/%D0%BA%D0%BE%D1%82/");
        assertThat(RedirectUrls.sanitize("http://wp.test/{a}|b^c`d")).isEqualTo("http://wp.test/abcd");
        assertThat(RedirectUrls.sanitize("http://wp.test/a\\b")).isEqualTo("http://wp.test/ab");
        assertThat(RedirectUrls.sanitize("http://wp.test/?q=a'b")).isEqualTo("http://wp.test/?q=ab");
        assertThat(RedirectUrls.sanitize("http://wp.test/~x/[y]/(z)/!*@,;:"))
                .isEqualTo("http://wp.test/~x/[y]/(z)/!*@,;:");
        assertThat(RedirectUrls.sanitize("http://wp.test/😀")).isEqualTo("http://wp.test/%F0%9F%98%80");
        assertThat(RedirectUrls.sanitize("http://wp.test/a\tb")).isEqualTo("http://wp.test/ab");
        assertThat(RedirectUrls.sanitize("http://wp.test/%0A%0D")).isEqualTo("http://wp.test/");
        // a lone surrogate is no character: deleted
        assertThat(RedirectUrls.sanitize("http://wp.test/a\uD83Db")).isEqualTo("http://wp.test/ab");
    }
}
