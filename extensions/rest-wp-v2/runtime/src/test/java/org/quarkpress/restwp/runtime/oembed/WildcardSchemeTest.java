package org.quarkpress.restwp.runtime.oembed;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * WordPress's wildcard provider schemes: {@code *} stands for one or more characters, everything else is literal and
 * compared ignoring ASCII case, a scheme starting with {@code http://} also matches {@code https://}, and the scheme
 * may match anywhere inside the URL.
 */
class WildcardSchemeTest {

    private static final String FLICKR = "http://*.flickr.com/photos/*";

    @Test
    void theReferenceCases() {
        // a leading http:// also matches https://, and the stars match one or more characters
        assertThat(WildcardScheme.matches(FLICKR, "https://www.flickr.com/photos/x"))
                .isTrue();
        assertThat(WildcardScheme.matches(FLICKR, "http://www.flickr.com/photos/x"))
                .isTrue();
        // unanchored: the URL may hold the match anywhere
        assertThat(WildcardScheme.matches(FLICKR, "see https://www.flickr.com/photos/x"))
                .isTrue();
        // a star needs at least one character
        assertThat(WildcardScheme.matches(FLICKR, "https://www.flickr.com/photos/"))
                .isFalse();
        assertThat(WildcardScheme.matches(FLICKR, "https://flickr.com/photos/x"))
                .isFalse();
        assertThat(WildcardScheme.matches("https://youtu.be/*", "https://youtu.be/"))
                .isFalse();
        assertThat(WildcardScheme.matches("https://youtu.be/*", "https://youtu.be/a"))
                .isTrue();
        // ASCII case does not matter
        assertThat(WildcardScheme.matches("https://*.youtube.com/watch*", "https://WWW.YouTube.COM/WATCH?v=1"))
                .isTrue();
        assertThat(WildcardScheme.matches("HTTPS://youtu.be/*", "https://youtu.be/a"))
                .isTrue();
        // only a leading http:// is widened; https:// stays https://
        assertThat(WildcardScheme.matches("https://example.com/a", "http://example.com/a"))
                .isFalse();
        assertThat(WildcardScheme.matches("http://example.com/a", "https://example.com/a"))
                .isTrue();
        // everything but the star is literal: a dot is a dot, a question mark a question mark
        assertThat(WildcardScheme.matches("https://x.com/*", "https://xacom/1")).isFalse();
        assertThat(WildcardScheme.matches(
                        "https://*.youtube.com/playlist?list=*", "https://www.youtube.com/playlist?list=a"))
                .isTrue();
        assertThat(WildcardScheme.matches(
                        "https://*.youtube.com/playlist?list=*", "https://www.youtube.com/playlistXlist=a"))
                .isFalse();
        // a star may span slashes and dots
        assertThat(WildcardScheme.matches("https://*.youtube.com/watch*", "https://a.b.youtube.com/watch/v/1"))
                .isTrue();
        // an empty scheme matches nothing; a scheme without stars is a plain search
        assertThat(WildcardScheme.matches("", "https://a.b/")).isFalse();
        assertThat(WildcardScheme.matches("b/", "https://a.b/")).isTrue();
        // a star at the start: unanchored, it may take the scheme itself
        assertThat(WildcardScheme.matches("*.flickr.com/*", "https://www.flickr.com/photos/x"))
                .isTrue();
        assertThat(WildcardScheme.matches("*.flickr.com/*", "https://.flickr.com/photos/x"))
                .isTrue();
        assertThat(WildcardScheme.matches("*.flickr.com/*", ".flickr.com/photos/x"))
                .isFalse();
    }

    @Test
    void longUrlsWithManyStarsAnswerQuickly() {
        StringBuilder pattern = new StringBuilder("https://*.a.com/");
        for (int i = 0; i < 19; i++) {
            pattern.append("*/");
        }
        pattern.append("end");
        StringBuilder url = new StringBuilder("https://www.a.com/");
        while (url.length() < 10_000) {
            url.append("segment/");
        }
        String matching = url + "end";
        String failing = url + "nope";
        long start = System.nanoTime();
        assertThat(WildcardScheme.matches(pattern.toString(), matching)).isTrue();
        assertThat(WildcardScheme.matches(pattern.toString(), failing)).isFalse();
        // a greedy walk, not a backtracking regex: generous bound for a loaded host
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2_000);
    }
}
