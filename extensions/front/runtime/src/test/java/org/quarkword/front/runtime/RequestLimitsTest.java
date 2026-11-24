package org.quarkword.front.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The bounds a front-end request must stay within before anything is parsed. */
class RequestLimitsTest {

    private static String repeat(String piece, int times) {
        return piece.repeat(times);
    }

    @Test
    void aPathOfTheLimitSizePasses() {
        String path = "/" + repeat("a", 2047);
        assertThat(path.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(2048);
        assertThat(RequestLimits.check(path, null)).isTrue();
        assertThat(RequestLimits.check("/" + repeat("a", 2048), null)).isFalse();
        // bytes, not characters: a two-byte character counts twice
        assertThat(RequestLimits.check("/" + repeat("é", 1024), null)).isFalse();
    }

    @Test
    void segmentsAreCounted() {
        assertThat(RequestLimits.check(repeat("/a", 32) + "/", "")).isTrue();
        assertThat(RequestLimits.check(repeat("/a", 33), "")).isFalse();
        assertThat(RequestLimits.check("////", "")).isTrue();
        assertThat(RequestLimits.check("/2025/11/20/hello-world/", "")).isTrue();
    }

    @Test
    void dotDotSegmentsAreRefused() {
        assertThat(RequestLimits.check("/a/../b/", "")).isFalse();
        assertThat(RequestLimits.check("/..", "")).isFalse();
        assertThat(RequestLimits.check("/a/./b/", "")).isTrue();
        assertThat(RequestLimits.check("/2025/11/20/hello-world/.", "")).isTrue();
        assertThat(RequestLimits.check("/a/...", "")).isTrue();
        // the percent-encoded forms decode to the same segment
        assertThat(RequestLimits.check("/a/%2E%2E/b/", "")).isFalse();
        assertThat(RequestLimits.check("/a/%2e%2e/b/", "")).isFalse();
        assertThat(RequestLimits.check("/a/.%2E/b/", "")).isFalse();
        assertThat(RequestLimits.check("/a/%2E./b/", "")).isFalse();
        assertThat(RequestLimits.check("/a/%2E/b/", "")).isTrue();
        assertThat(RequestLimits.check("/a/%2E%2E%2E/b/", "")).isTrue();
    }

    @Test
    void percentEncodingMustBeWellFormed() {
        assertThat(RequestLimits.check("/caf%C3%A9/", "")).isTrue();
        assertThat(RequestLimits.check("/caf%c3%a9/", "")).isTrue();
        assertThat(RequestLimits.check("/café/", "")).isTrue();
        assertThat(RequestLimits.check("/a%ZZb/", "")).isFalse();
        assertThat(RequestLimits.check("/a%/", "")).isFalse();
        assertThat(RequestLimits.check("/a%2", "")).isFalse();
        assertThat(RequestLimits.check("/a%00b/", "")).isFalse();
        assertThat(RequestLimits.check("/a\u0000b/", "")).isFalse();
        // an overlong encoding of "/" and an encoded surrogate are not UTF-8
        assertThat(RequestLimits.check("/a%C0%AFb/", "")).isFalse();
        assertThat(RequestLimits.check("/a%ED%A0%80b/", "")).isFalse();
        assertThat(RequestLimits.check("/a%E2%82/", "")).isFalse();
        assertThat(RequestLimits.check("/a%2Fb/", "")).isTrue();
        assertThat(RequestLimits.check("/a/", "s=%ZZ")).isFalse();
        assertThat(RequestLimits.check("/a/", "s=caf%C3%A9&x=1")).isTrue();
    }

    @Test
    void queryParametersAreCounted() {
        StringBuilder query = new StringBuilder();
        for (int i = 1; i <= 64; i++) {
            query.append(i > 1 ? "&" : "").append("p").append(i).append("=1");
        }
        assertThat(RequestLimits.check("/", query.toString())).isTrue();
        assertThat(RequestLimits.check("/", query + "&p65=1")).isFalse();
        assertThat(RequestLimits.check("/", null)).isTrue();
        assertThat(RequestLimits.check("/", "")).isTrue();
        assertThat(RequestLimits.check("/", "&&&")).isTrue();
        assertThat(RequestLimits.check("/", "s=" + repeat("x", 16_000))).isTrue();
    }

    @Test
    void aMissingPathIsRefused() {
        assertThat(RequestLimits.check(null, "")).isFalse();
    }
}
