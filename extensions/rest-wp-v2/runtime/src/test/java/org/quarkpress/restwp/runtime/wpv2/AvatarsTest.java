package org.quarkpress.restwp.runtime.wpv2;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.SiteOptions;

class AvatarsTest {

    @Test
    void gravatarSha256OfTheTrimmedLowerCasedEmail() {
        ObjectNode urls = Avatars.urls(Optional.of("  Carol@QuarkPress.test "), options(Map.of()))
                .orElseThrow();
        String hash = sha256Hex("carol@quarkpress.test");
        // the hash WordPress gave carol@quarkpress.test (REST verification list item 24)
        assertThat(hash).isEqualTo("b2b80e9a91cf0031a0929970d723ec1a622ab9f122eedb00a13c11a1caa42f9c");
        assertThat(urls.toString())
                .isEqualTo("{\"24\":\"https://secure.gravatar.com/avatar/" + hash + "?s=24&d=mm&r=g\","
                        + "\"48\":\"https://secure.gravatar.com/avatar/" + hash + "?s=48&d=mm&r=g\","
                        + "\"96\":\"https://secure.gravatar.com/avatar/" + hash + "?s=96&d=mm&r=g\"}");
        // no address (or PHP's false "0"): WordPress hashes nothing
        assertThat(Avatars.urls(Optional.empty(), options(Map.of()))
                        .orElseThrow()
                        .get("24")
                        .asText())
                .isEqualTo("https://secure.gravatar.com/avatar/?s=24&d=mm&r=g");
        assertThat(Avatars.urls(Optional.of(""), options(Map.of()))
                        .orElseThrow()
                        .get("96")
                        .asText())
                .isEqualTo("https://secure.gravatar.com/avatar/?s=96&d=mm&r=g");
        assertThat(Avatars.urls(Optional.of("0"), options(Map.of()))
                        .orElseThrow()
                        .get("48")
                        .asText())
                .isEqualTo("https://secure.gravatar.com/avatar/?s=48&d=mm&r=g");
        assertThat(Avatars.urls(Optional.of("a@b.c"), options(Map.of("show_avatars", ""))))
                .isEmpty();
        assertThat(Avatars.urls(Optional.of("a@b.c"), options(Map.of("show_avatars", "0"))))
                .isEmpty();
        assertThat(Avatars.urls(Optional.of("a@b.c"), options(Map.of("show_avatars", "1"))))
                .isPresent();
        assertThat(Avatars.urls(
                                Optional.of("a@b.c"),
                                options(Map.of("avatar_default", "identicon", "avatar_rating", "PG")))
                        .orElseThrow()
                        .get("24")
                        .asText())
                .endsWith("?s=24&d=identicon&r=pg");
        assertThat(Avatars.urls(Optional.of("a@b.c"), options(Map.of("avatar_default", "mystery")))
                        .orElseThrow()
                        .get("96")
                        .asText())
                .endsWith("?s=96&d=mm&r=g");
    }

    @Test
    void theDefaultImageAndRatingOptions() {
        assertThat(url(Map.of("avatar_default", "mysteryman"))).endsWith("?s=24&d=mm&r=g");
        assertThat(url(Map.of("avatar_default", "gravatar_default"))).endsWith("?s=24&r=g");
        assertThat(url(Map.of("avatar_default", ""))).endsWith("?s=24&r=g");
        assertThat(url(Map.of("avatar_rating", ""))).endsWith("?s=24&d=mm");
        assertThat(url(Map.of("avatar_default", "", "avatar_rating", "")))
                .endsWith("/avatar/" + sha256Hex("a@b.c") + "?s=24");
        assertThat(url(Map.of("avatar_default", "blank", "avatar_rating", "X"))).endsWith("?s=24&d=blank&r=x");
    }

    @Test
    void theInitialsDefaultAddsTheNamesInitialsLast() {
        Map<String, String> initials = Map.of("avatar_default", "initials");
        assertThat(named("a@b.c", "Jane Doe", initials))
                .isEqualTo("https://secure.gravatar.com/avatar/" + sha256Hex("a@b.c")
                        + "?s=24&d=initials&r=g&initials=JD");
        // the first character and the one after the last space; the case is kept
        assertThat(named("a@b.c", "jane mary doe", initials)).endsWith("&initials=jd");
        // one word: its first two characters
        assertThat(named("a@b.c", "Cher", initials)).endsWith("&initials=Ch");
        assertThat(named("a@b.c", "X", initials)).endsWith("&initials=X");
        // a Han, Hiragana, Katakana or Hangul character: the first two characters, rawurlencoded
        String han = new String(new int[] {0x5C71, 0x7530, 0x0020, 0x592A, 0x90CE}, 0, 5);
        assertThat(named("a@b.c", han, initials)).endsWith("&initials=%E5%B1%B1%E7%94%B0");
        // WordPress takes the last space's byte offset as a character offset: "Zo" + e-diaeresis + " Smith" gives "Zm"
        assertThat(named("a@b.c", "Zo\u00EB Smith", initials)).endsWith("&initials=Zm");
        // a trailing space: only the first character
        assertThat(named("a@b.c", "Jane ", initials)).endsWith("&initials=J");
        // no address: no hash, the initials still added
        assertThat(named("", "Jane Doe", initials))
                .isEqualTo("https://secure.gravatar.com/avatar/?s=24&d=initials&r=g&initials=JD");
        // no name: no initials parameter; another default: none either
        assertThat(named("a@b.c", "", initials)).endsWith("?s=24&d=initials&r=g");
        assertThat(named("a@b.c", "Jane Doe", Map.of())).endsWith("?s=24&d=mm&r=g");
        assertThat(named("a@b.c", "Jane Doe", Map.of("avatar_default", "initials", "avatar_rating", "")))
                .endsWith("?s=24&d=initials&initials=JD");
    }

    private static String named(String email, String name, Map<String, String> values) {
        return Avatars.urls(Optional.of(email), name, options(values))
                .orElseThrow()
                .get("24")
                .asText();
    }

    private static String url(Map<String, String> values) {
        return Avatars.urls(Optional.of("a@b.c"), options(values))
                .orElseThrow()
                .get("24")
                .asText();
    }

    @Test
    void onlyAsciiLettersAreLowerCasedAndOnlyPhpWhitespaceTrimmed() {
        // PHP's strtolower leaves non-ASCII letters alone; trim strips tabs and line ends but not a no-break space
        String upper = "\u00C9@X.TEST";
        assertThat(Avatars.urls(Optional.of("\t" + upper + "\n"), options(Map.of()))
                        .orElseThrow()
                        .get("24")
                        .asText())
                .contains(sha256Hex("\u00C9@x.test"));
        assertThat(Avatars.urls(Optional.of("\u00A0a@b.c"), options(Map.of()))
                        .orElseThrow()
                        .get("24")
                        .asText())
                .contains(sha256Hex("\u00A0a@b.c"));
    }

    private static String sha256Hex(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Site options holding {@code values} as texts. */
    private static SiteOptions options(Map<String, String> values) {
        return new SiteOptions() {
            @Override
            public Optional<JsonNode> get(String name) {
                return Optional.ofNullable(values.get(name)).map(TextNode::valueOf);
            }

            @Override
            public String string(String name, String fallback) {
                return values.getOrDefault(name, fallback);
            }

            @Override
            public long number(String name, long fallback) {
                return Optional.ofNullable(values.get(name))
                        .map(Long::parseLong)
                        .orElse(fallback);
            }

            @Override
            public boolean flag(String name, boolean fallback) {
                String value = values.get(name);
                return value == null ? fallback : !(value.isEmpty() || value.equals("0"));
            }
        };
    }
}
