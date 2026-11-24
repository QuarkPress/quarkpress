package org.quarkword.front.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** WordPress 7.1.3's {@code set_url_scheme} answers, recorded with PHP. */
class UrlSchemesTest {

    @Test
    void theFixedModesAreThemselves() {
        assertThat(UrlSchemes.set("http://wp.test/x", "https", false, false)).isEqualTo("https://wp.test/x");
        assertThat(UrlSchemes.set("https://wp.test/x", "http", true, true)).isEqualTo("http://wp.test/x");
        assertThat(UrlSchemes.set(" http://wp.test/x ", "https", false, false)).isEqualTo("https://wp.test/x");
    }

    @Test
    void relativeStripsSchemeAndHostAndCollapsesLeadingSlashes() {
        assertThat(UrlSchemes.set("https://wp.test/x", "relative", false, false))
                .isEqualTo("/x");
        assertThat(UrlSchemes.set("//wp.test/x", "relative", false, false)).isEqualTo("/x");
        assertThat(UrlSchemes.set("http://wp.test//x", "relative", false, false))
                .isEqualTo("/x");
        assertThat(UrlSchemes.set("/x", "relative", false, false)).isEqualTo("/x");
        assertThat(UrlSchemes.set("http://wp.test", "relative", false, false)).isEmpty();
    }

    @Test
    void theAdminModesFollowTheRequestOrForceSslAdmin() {
        assertThat(UrlSchemes.set("http://wp.test/wp-admin/", "admin", false, true))
                .isEqualTo("https://wp.test/wp-admin/");
        assertThat(UrlSchemes.set("http://wp.test/wp-admin/", "admin", true, false))
                .isEqualTo("https://wp.test/wp-admin/");
        assertThat(UrlSchemes.set("http://wp.test/x", "admin", false, false)).isEqualTo("http://wp.test/x");
        assertThat(UrlSchemes.set("http://wp.test/x", "login", false, true)).isEqualTo("https://wp.test/x");
        assertThat(UrlSchemes.set("http://wp.test/x", "login_post", false, true))
                .isEqualTo("https://wp.test/x");
        assertThat(UrlSchemes.set("http://wp.test/x", "rpc", false, true)).isEqualTo("https://wp.test/x");
    }

    @Test
    void anyOtherModeFollowsTheRequest() {
        assertThat(UrlSchemes.set("http://a/b", "rest", true, false)).isEqualTo("https://a/b");
        assertThat(UrlSchemes.set("http://a/b", "rest", false, false)).isEqualTo("http://a/b");
        assertThat(UrlSchemes.set("http://a/b", "rest", false, true)).isEqualTo("http://a/b");
        assertThat(UrlSchemes.set("https://wp.test/x", null, false, false)).isEqualTo("http://wp.test/x");
        assertThat(UrlSchemes.set("http://wp.test/x", "", true, false)).isEqualTo("https://wp.test/x");
    }

    @Test
    void protocolRelativeInputGetsHttpFirst() {
        assertThat(UrlSchemes.set("//wp.test/x", "https", false, false)).isEqualTo("https://wp.test/x");
        assertThat(UrlSchemes.set("//wp.test/x", null, false, false)).isEqualTo("http://wp.test/x");
    }

    @Test
    void aValueWithoutASchemeIsUnchanged() {
        assertThat(UrlSchemes.set("x", "https", false, false)).isEqualTo("x");
        assertThat(UrlSchemes.set("wp.test/x", "https", false, false)).isEqualTo("wp.test/x");
    }
}
