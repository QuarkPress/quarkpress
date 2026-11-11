package org.quarkpress.media.storage.s3.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class S3SettingsTest {

    record Config(
            Optional<String> bucket,
            Optional<String> keyPrefix,
            Optional<String> publicBaseUrl,
            Optional<Duration> presignedUrlTtl,
            boolean conditionalWrites)
            implements S3Config {}

    private static Config bucketOnly() {
        return new Config(Optional.of("media"), Optional.empty(), Optional.empty(), Optional.empty(), true);
    }

    private static Config withPrefix(String prefix) {
        return new Config(Optional.of("media"), Optional.of(prefix), Optional.empty(), Optional.empty(), true);
    }

    private static Config withTtl(String ttl) {
        return new Config(
                Optional.of("media"), Optional.empty(), Optional.empty(), Optional.of(Duration.parse(ttl)), true);
    }

    @Test
    void bucketIsRequired() {
        Config none = new Config(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), true);
        assertThatThrownBy(() -> S3Settings.of(none))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("quarkpress.media.s3.bucket is required by quarkpress-media-storage-s3");
        Config empty = new Config(Optional.of(""), Optional.empty(), Optional.empty(), Optional.empty(), true);
        assertThatThrownBy(() -> S3Settings.of(empty))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("quarkpress.media.s3.bucket is required by quarkpress-media-storage-s3");
    }

    @Test
    void theSettingsAreKept() {
        S3Settings settings = S3Settings.of(new Config(
                Optional.of("media"),
                Optional.of("site"),
                Optional.of("https://cdn.example.com"),
                Optional.of(Duration.ofHours(1)),
                false));
        assertThat(settings)
                .isEqualTo(new S3Settings(
                        "media",
                        "site",
                        Optional.of("https://cdn.example.com"),
                        Optional.of(Duration.ofHours(1)),
                        false));
    }

    @Test
    void keyPrefixRules() {
        assertThat(S3Settings.of(withPrefix("media")).keyPrefix()).isEqualTo("media");
        assertThat(S3Settings.of(withPrefix("a/b")).keyPrefix()).isEqualTo("a/b");
        assertThat(S3Settings.of(bucketOnly()).keyPrefix()).isEmpty();
        for (String prefix : new String[] {"/media", "media/"}) {
            assertThatThrownBy(() -> S3Settings.of(withPrefix(prefix)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("quarkpress.media.s3.key-prefix has no leading or trailing /: " + prefix);
        }
        assertThatThrownBy(() -> S3Settings.of(withPrefix("a//b")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("quarkpress.media.s3.key-prefix is not a valid key prefix: "
                        + "a storage key has no empty segment: a//b");
        assertThatThrownBy(() -> S3Settings.of(withPrefix("a/../b")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("quarkpress.media.s3.key-prefix is not a valid key prefix: "
                        + "a storage key has no . or .. segment: a/../b");
    }

    @Test
    void presignedTtlBounds() {
        assertThat(S3Settings.of(withTtl("P7D")).presignedUrlTtl()).contains(Duration.ofDays(7));
        assertThat(S3Settings.MAX_PRESIGNED_TTL).isEqualTo(Duration.ofDays(7));
        for (String ttl : new String[] {"P7DT1S", "PT0S", "-PT1M"}) {
            assertThatThrownBy(() -> S3Settings.of(withTtl(ttl)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("quarkpress.media.s3.presigned-url-ttl is longer than 0 and at most 7 days (P7D), not "
                            + Duration.parse(ttl));
        }
    }

    @Test
    void publicBaseUrlLosesTrailingSlashes() {
        Config config = new Config(
                Optional.of("media"),
                Optional.empty(),
                Optional.of("https://cdn.example.com/m//"),
                Optional.empty(),
                true);
        assertThat(S3Settings.of(config).publicBaseUrl()).contains("https://cdn.example.com/m");
    }
}
