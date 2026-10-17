package org.quarkpress.lib.wpshortcode;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UrlProtocolsTest {

    private static void assertStripsProtocols(String value, String expected) {
        assertThat(UrlProtocols.strip(value)).as(value).isEqualTo(expected);
    }

    @Test
    void stripsDisallowedProtocolsLikeWordPress() {
        assertStripsProtocols("http://x", "http://x");
        assertStripsProtocols("https://x", "https://x");
        assertStripsProtocols("javascript:alert(1)", "alert(1)");
        assertStripsProtocols("JavaScript:alert(1)", "alert(1)");
        assertStripsProtocols("java&#115;cript:x", "x");
        assertStripsProtocols("jav&#x61;script:x", "x");
        assertStripsProtocols("feed:http://x", "feed:http://x");
        assertStripsProtocols("feed:javascript:x", "feed:x");
        assertStripsProtocols("feed:feed:feed:http://x", "");
        assertStripsProtocols("feed:0", "0");
        assertStripsProtocols("mailto:a@b", "mailto:a@b");
        assertStripsProtocols("MAILTO:a@b", "mailto:a@b");
        assertStripsProtocols("foo:bar", "bar");
        assertStripsProtocols("a/?b:c", "a/?b:c");
        assertStripsProtocols("a/b:c", "c");
        assertStripsProtocols("jav\tascript:x", "x");
        assertStripsProtocols(" javascript:x", "x");
        assertStripsProtocols("javascript&#58;x", "x");
        assertStripsProtocols("javascript&#058x", "x");
        assertStripsProtocols("javascript&#x3A;x", "x");
        assertStripsProtocols("javascript&#x3ax", "x");
        assertStripsProtocols("javascript&colon;x", "x");
        assertStripsProtocols("javascript&COLON;x", "x");
        assertStripsProtocols("x:y:z", "z");
        assertStripsProtocols("http:x", "http:x");
        assertStripsProtocols("ht\\0tp:x", "http:x");
        assertStripsProtocols("no colon", "no colon");
        assertStripsProtocols("a\u0001b:x", "x");
        assertStripsProtocols("tel: 123 ", "tel:123");
        assertStripsProtocols("urn:a:b", "urn:a:b");
        assertStripsProtocols("&#104;ttp://x", "http://x");
        assertStripsProtocols("&#38;#106;avascript:x", "x");
        assertStripsProtocols("irc6://x", "irc6://x");
    }

    @Test
    void knowsTheUriAttributes() {
        assertThat(UrlProtocols.isUriAttribute("href")).isTrue();
        assertThat(UrlProtocols.isUriAttribute("src")).isTrue();
        assertThat(UrlProtocols.isUriAttribute("title")).isFalse();
        assertThat(UrlProtocols.ALLOWED).hasSize(22).contains("http", "irc6", "urn");
    }
}
