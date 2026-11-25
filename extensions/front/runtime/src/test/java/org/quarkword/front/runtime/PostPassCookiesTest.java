package org.quarkword.front.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkword.lib.wppassword.WpPasswords;

/**
 * The post-password cookie as WordPress 7.1.3 names, writes and reads it (postpass.out and the Task 11 cookie probe in
 * the slice folder, recorded with /usr/bin/php; digests from {@code php -r 'echo md5(…);'}).
 */
class PostPassCookiesTest {

    /** {@code md5("http://wp.test")}. */
    static final String SINGLE = "wp-postpass_12913fd91550a6158ad37f2c7911fba9";

    /** {@code md5("http://wp.test/")}: the network's {@code siteurl} carries a trailing slash. */
    static final String NETWORK = "wp-postpass_f247b58bb6fdc61f0d164edf55afad99";

    static final Instant NOW = Instant.parse("2026-11-25T08:18:56Z");

    /** Six fixed salt bytes: {@code PasswordHash(8, true)} writes {@code s3} with them as the hash below. */
    static SecureRandom salt() {
        return new SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                for (int i = 0; i < bytes.length; i++) {
                    bytes[i] = (byte) i;
                }
            }
        };
    }

    static final String S3_HASH = "$P$B.2U.1EE/dMhBep4Vg9bYh03J1YC6x0";

    @Test
    void theNameIsTheMd5OfTheSiteurlOnASingleSiteAndOfTheNetworksOnAMultisite() {
        assertThat(PostPassCookies.name(PostPassCookies.hashInput(false, "http://wp.test")))
                .isEqualTo(SINGLE);
        assertThat(PostPassCookies.name(PostPassCookies.hashInput(true, "http://wp.test")))
                .isEqualTo(NETWORK);
        // WordPress uses an empty hash (not the md5 of "") for a siteurl PHP reads as false
        assertThat(PostPassCookies.name(PostPassCookies.hashInput(false, ""))).isEqualTo("wp-postpass_");
        assertThat(PostPassCookies.name(PostPassCookies.hashInput(false, "0"))).isEqualTo("wp-postpass_");
    }

    @Test
    void theSetCookieHeaderIsWordPresssSixArgumentSetcookie() {
        assertThat(PostPassCookies.setCookie(SINGLE, "s3", false, "/", NOW, salt()))
                .contains(SINGLE + "=%24P%24B.2U.1EE%2FdMhBep4Vg9bYh03J1YC6x0; expires=Sat, 05 Dec 2026 08:18:56 GMT;"
                        + " Max-Age=864000; path=/");
        assertThat(PostPassCookies.setCookie(SINGLE, "s3", true, "/site2/", NOW, salt()))
                .contains(SINGLE + "=%24P%24B.2U.1EE%2FdMhBep4Vg9bYh03J1YC6x0; expires=Sat, 05 Dec 2026 08:18:56 GMT;"
                        + " Max-Age=864000; path=/site2/; secure");
        String fresh = PostPassCookies.setCookie(SINGLE, "s3", false, "/", NOW, new SecureRandom())
                .orElseThrow();
        assertThat(fresh).doesNotContainIgnoringCase("httponly").doesNotContainIgnoringCase("samesite");
        assertThat(fresh).doesNotContainIgnoringCase("domain");
    }

    @Test
    void aPasswordOver4096BytesGetsWordPresssStar() {
        assertThat(PostPassCookies.setCookie(SINGLE, "a".repeat(4097), false, "/", NOW, salt()))
                .contains(SINGLE + "=%2A; expires=Sat, 05 Dec 2026 08:18:56 GMT; Max-Age=864000; path=/");
    }

    @Test
    void aPathPhpRefusesSetsNoCookie() {
        for (String path : new String[] {"/a;b/", "/a b/", "/a,b/", "/a\tb/", "/a\r\nb/"}) {
            assertThat(PostPassCookies.setCookie(SINGLE, "s3", false, path, NOW, salt()))
                    .as(path)
                    .isEmpty();
        }
    }

    @Test
    void theCookiePathIsHomesPathWithATrailingSlash() {
        assertThat(PostPassCookies.cookiePathOf("http://wp.test")).isEqualTo("/");
        assertThat(PostPassCookies.cookiePathOf("http://wp.test:8081")).isEqualTo("/");
        assertThat(PostPassCookies.cookiePathOf("http://wp.test/site2")).isEqualTo("/site2/");
        assertThat(PostPassCookies.cookiePathOf("HTTPS://wp.test/a/b")).isEqualTo("/a/b/");
        assertThat(PostPassCookies.cookiePathOf("")).isEqualTo("/");
    }

    @Test
    void theCookieIsReadAsPhpFillsCookie() {
        assertThat(read("wp-postpass_x=%24P%24Babc%2Fd")).contains("$P$Babc/d");
        assertThat(read("wp-postpass_x=$P$Ba+b")).contains("$P$Ba+b");
        assertThat(read("wp-postpass_x=first; wp-postpass_x=second")).contains("first");
        assertThat(read("a=1;wp-postpass_x=nospace")).contains("nospace");
        assertThat(read("\twp-postpass_x=tab")).contains("tab");
        assertThat(read("wp-postpass_x")).contains("");
        assertThat(read("wp-postpass_x=")).contains("");
        assertThat(read("wp-postpass_x=a=b")).contains("a=b");
        assertThat(read("wp-postpass_x=\"q\"")).contains("\"q\"");
        assertThat(read("wp-postpass_x=%zz%2")).contains("%zz%2");
        // a name is matched after PHP's own changes: spaces and dots become underscores, encoding is kept
        assertThat(read("wp-postpass x=space")).contains("space");
        assertThat(read("wp-postpass.x=dot")).contains("dot");
        // a name with brackets makes PHP's value an array, whatever came before: present, and never a hash here
        assertThat(read("wp-postpass_x=a;wp-postpass_x[a]=arr")).contains("");
        assertThat(read("wp-postpass_x[a]=arr; wp-postpass_x=b")).contains("");
        assertThat(read("wp-postpass%5Fx=enc")).isEmpty();
        assertThat(read(" wp-postpass_x = sp ")).isEmpty();
        assertThat(read("a=1, wp-postpass_x=comma")).isEmpty();
        assertThat(PostPassCookies.value(List.of("a=1", "wp-postpass_x=second-header"), "wp-postpass_x"))
                .contains("second-header");
        assertThat(PostPassCookies.value(List.of(), "wp-postpass_x")).isEmpty();
    }

    private static Optional<String> read(String header) {
        return PostPassCookies.value(List.of(header), "wp-postpass_x");
    }

    @Test
    void onlyAPortablePhpassHashOfThePasswordUnlocks() {
        var post = PasswordFormTest.post(9, "s3");
        assertThat(PostPassCookies.unlocked(post, Optional.empty())).isFalse();
        assertThat(PostPassCookies.unlocked(post, Optional.of("s3"))).isFalse();
        assertThat(PostPassCookies.unlocked(post, Optional.of(S3_HASH))).isTrue();
        assertThat(PostPassCookies.unlocked(post, Optional.of(WpPasswords.hashPortable("s3", 8, new SecureRandom()))))
                .isTrue();
        assertThat(PostPassCookies.unlocked(
                        post, Optional.of(WpPasswords.hashPortable("wrong", 8, new SecureRandom()))))
                .isFalse();
        assertThat(PostPassCookies.unlocked(post, Optional.of("$H$" + S3_HASH.substring(3))))
                .isFalse();
        assertThat(PostPassCookies.unlocked(post, Optional.of("$P$C" + S3_HASH.substring(4))))
                .isFalse();
        assertThat(PostPassCookies.unlocked(post, Optional.of(S3_HASH + "x"))).isFalse();
        assertThat(PostPassCookies.unlocked(post, Optional.of(""))).isFalse();
        // WordPress's own cookie for s3, as its postpass action wrote it and PHP decoded it
        assertThat(PostPassCookies.unlocked(post, Optional.of("$P$BZCIJPM9ItMyWwAIMeTCxVh0Ia5ViV0")))
                .isTrue();
        // a post without a password, or with "0", needs none
        assertThat(PostPassCookies.unlocked(PasswordFormTest.post(9, ""), Optional.empty()))
                .isTrue();
        assertThat(PostPassCookies.unlocked(PasswordFormTest.post(9, "0"), Optional.empty()))
                .isTrue();
        // a cookie for s3 unlocks every post whose password is s3, and no other
        assertThat(PostPassCookies.unlocked(PasswordFormTest.post(12, "s3"), Optional.of(S3_HASH)))
                .isTrue();
        assertThat(PostPassCookies.unlocked(PasswordFormTest.post(12, "other"), Optional.of(S3_HASH)))
                .isFalse();
    }

    @Test
    void theInvalidNoticeNeedsTheReferrerToBeThePermalinkAndTheCookieToExist() {
        String permalink = "http://wp.test/?p=9";
        assertThat(PostPassCookies.invalid(Optional.of(permalink), permalink, true))
                .isTrue();
        assertThat(PostPassCookies.invalid(Optional.of(permalink), permalink, false))
                .isFalse();
        assertThat(PostPassCookies.invalid(Optional.of(permalink + "&x=1"), permalink, true))
                .isFalse();
        assertThat(PostPassCookies.invalid(Optional.empty(), permalink, true)).isFalse();
    }

    @Test
    void theRawReferrerPrefersTheRequestsReferrerField() {
        assertThat(PostPassCookies.rawReferer(Optional.of("http://wp.test/?p=7"), Optional.of("http://wp.test/?p=9")))
                .contains("http://wp.test/?p=7");
        // PHP's empty(): "" and "0" fall through to the header
        assertThat(PostPassCookies.rawReferer(Optional.of("0"), Optional.of("http://wp.test/?p=9")))
                .contains("http://wp.test/?p=9");
        assertThat(PostPassCookies.rawReferer(Optional.of(""), Optional.of("http://wp.test/?p=9")))
                .contains("http://wp.test/?p=9");
        assertThat(PostPassCookies.rawReferer(Optional.empty(), Optional.of("0")))
                .isEmpty();
        assertThat(PostPassCookies.rawReferer(Optional.empty(), Optional.empty()))
                .isEmpty();
    }

    @Test
    void anArrayRefererFieldIsIgnoredForTheHeader() {
        // php postpass-fix.php: wp_get_raw_referer skips a _wp_http_referer that is no string
        Optional<String> header = Optional.of("http://wp.test/?p=9");
        assertThat(FrontPage.rawReferer(
                        FrontPage.query("_wp_http_referer%5B%5D=http%3A%2F%2Fwp.test%2F%3Fp%3D7"), header))
                .contains("http://wp.test/?p=9");
        assertThat(FrontPage.rawReferer(FrontPage.query("_wp_http_referer[0]=http%3A%2F%2Fwp.test%2F%3Fp%3D7"), header))
                .contains("http://wp.test/?p=9");
        assertThat(FrontPage.rawReferer(FrontPage.query("_wp_http_referer=http%3A%2F%2Fwp.test%2F%3Fp%3D7"), header))
                .contains("http://wp.test/?p=7");
    }

    @Test
    void onlyTheFirstThousandNamedCookiesAreRead() {
        // php -S probe: max_input_vars counts cookies with a name; "=x" pairs are skipped uncounted
        StringBuilder named = new StringBuilder();
        for (int i = 0; i < 999; i++) {
            named.append('v').append(i).append("=1; ");
        }
        assertThat(read("=x; =x; =x; =x; =x; " + named + "wp-postpass_x=y")).contains("y");
        assertThat(read(named + "v999=1; wp-postpass_x=y")).isEmpty();
    }
}
