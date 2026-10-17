package org.quarkpress.lib.wpkses;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class KsesEngineTest {

    private static final KsesConfig BARE = KsesConfig.defaults(false, Examples.UPLOAD_URL);

    private static String kses(String html, AllowedHtml allowed) {
        return KsesRun.sanitize(BARE, html, allowed, List.of());
    }

    @Test
    void matchesWordPress() {
        Examples.assertFile("kses-bare.json");
    }

    @Test
    void valueCallbacksDecide() {
        // R9.6 (verified): value_callback v == 'ok'
        Allowlist allowlist = Allowlist.builder()
                .attribute("a", "x-cb", AttributeRule.of(AttributeCheck.callback("ok"::equals)))
                .build();
        assertThat(kses("<a x-cb=\"ok\">", allowlist)).isEqualTo("<a x-cb=\"ok\">");
        assertThat(kses("<a x-cb=\"no\">", allowlist)).isEqualTo("<a>");
    }

    @Test
    void entitiesContextRemovesNumericStartTags() {
        // README #2: WordPress throws a TypeError for these.
        assertThat(kses("<5>x", KsesContext.ENTITIES)).isEqualTo("x");
        assertThat(kses("<12 a=1>y", KsesContext.ENTITIES)).isEqualTo("y");
    }

    @Test
    void ksesIsNotIdempotent() {
        // Harness-verified: every pass over "<!a<b>>" adds another "&gt;".
        String once = kses("<!a<b>>", KsesContext.POST);
        assertThat(once).isEqualTo("<!a<b>>&gt;");
        assertThat(kses(once, KsesContext.POST)).isEqualTo("<!a<b>>&gt;&gt;");
    }

    @Test
    void commentsThatNeverSettleAreDropped() {
        // README #9 (controller ruling #9): WordPress loops forever on this input in bare mode.
        assertThat(assertTimeoutPreemptively(
                        Duration.ofSeconds(10), () -> kses("a<!-- <!a<b>> -->b", KsesContext.POST)))
                .isEqualTo("ab");
    }

    @Test
    void deepCommentRecursionIsBounded() {
        String nested = "<!--" + "<".repeat(5_000) + "!--".repeat(5_000) + "x";
        String bogus = "<!a" + "<!a".repeat(100_000) + ">".repeat(100_000);
        // Harness-verified at these sizes: the unclosed comment vanishes; the outer bogus comment keeps only
        // "a", and each of the other 99,999 '>' becomes "&gt;".
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            assertThat(kses(nested, KsesContext.POST)).isEmpty();
            assertThat(kses(bogus, KsesContext.POST)).isEqualTo("<!a>" + "&gt;".repeat(99_999));
        });
    }

    @Test
    void explicitAllowlistsAreUsedAsGiven() {
        Allowlist onlyB = Allowlist.builder().element("b").build();
        assertThat(kses("<b class=\"c\">x</b><i>y</i>", onlyB)).isEqualTo("<b>x</b>y");
    }

    @Test
    void aFalsyDataWildcardAllowsNoDataAttributes() throws Exception {
        // Harness-verified: wp_kses('<a data-x="1" title="t">', ['a' => ['data-*' => $v]]) for each $v.
        for (String falsy : List.of("false", "0", "\"0\"", "\"\"", "null", "[]", "{}")) {
            Allowlist allowlist = AllowlistJson.fromPhp(Examples.JSON.readTree("{\"a\":{\"data-*\":" + falsy + "}}"));
            assertThat(kses("<a data-x=\"1\" title=\"t\">", allowlist))
                    .as(falsy)
                    .isEqualTo("<a>");
        }
        Allowlist truthy = AllowlistJson.fromPhp(Examples.JSON.readTree("{\"a\":{\"data-*\":true}}"));
        assertThat(kses("<a data-x=\"1\" title=\"t\">", truthy)).isEqualTo("<a data-x=\"1\">");
        // '' under an attribute's own name falls through to the wildcard.
        Allowlist emptyName =
                AllowlistJson.fromPhp(Examples.JSON.readTree("{\"a\":{\"data-*\":true,\"data-x\":\"\"}}"));
        assertThat(kses("<a data-x=\"1\" data-y=\"2\">", emptyName)).isEqualTo("<a data-x=\"1\" data-y=\"2\">");
    }

    @Test
    void anEmptyProtocolListMeansTheDefault() {
        assertThat(KsesRun.sanitize(BARE, "<a href=\"mailto:a@b\">m</a>", KsesContext.POST, List.of()))
                .isEqualTo("<a href=\"mailto:a@b\">m</a>");
    }
}
