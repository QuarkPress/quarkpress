package org.quarkpress.lib.wpkses;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.quarkpress.testing.gen.Gens;
import org.quarkpress.testing.gen.Properties;

/**
 * Generated inputs (testing/test-gen). kses is not idempotent (WordPress's own output changes on a second pass,
 * see KsesEngineTest#ksesIsNotIdempotent), so no idempotence property is checked; re-sanitized output must
 * still be safe.
 */
class KsesPropertiesTest {

    private static final Kses WP = Kses.wordPress();
    private static final Kses BARE = Kses.builder().preFilters(false).build();
    private static final SafetyChecker POST =
            new SafetyChecker(WP.allowedHtml(KsesContext.POST), WP.uriAttributes(), WP.protocols());
    private static final SafetyChecker DATA =
            new SafetyChecker(WP.allowedHtml(KsesContext.DATA), WP.uriAttributes(), WP.protocols());

    @Test
    void soupOutputIsSafe() {
        Properties.check("kses soup is safe", 3_000, KsesArbitraries.soup(), html -> {
            assertThat(POST.problems(WP.post(html))).as("wp").isEmpty();
            assertThat(POST.problems(BARE.post(html))).as("bare").isEmpty();
            assertThat(DATA.problems(WP.data(html))).as("data").isEmpty();
            assertThat(POST.problems(WP.post(WP.post(html)))).as("twice").isEmpty();
        });
    }

    @Test
    void tagOutputIsSafe() {
        Properties.check("kses tags are safe", 3_000, KsesArbitraries.tags(), html -> {
            assertThat(POST.problems(WP.post(html))).isEmpty();
            assertThat(DATA.problems(WP.data(html))).isEmpty();
        });
    }

    @Test
    void protocolFilterResultsHaveAnAllowedSchemeOrNone() {
        Properties.check("bad protocol", 3_000, KsesArbitraries.urls(), url -> {
            String safe = WP.badProtocol(url);
            int colon = safe.indexOf(':');
            if (colon >= 0 && !safe.substring(0, colon).contains("/?")) {
                assertThat(WP.protocols()).contains(safe.substring(0, colon));
            }
        });
    }

    @Test
    void cssOutputKeepsOnlyAllowedDeclarations() {
        Properties.check("safe css", 3_000, KsesArbitraries.css(), css -> {
            for (String declaration : WP.safeCss(css).split(";", -1)) {
                int colon = declaration.indexOf(':');
                if (colon >= 0) {
                    String property = TextUtils.phpTrim(declaration.substring(0, colon));
                    assertThat(WP.safeStyleProperties().contains(property) || SafeCss.isCustomPropertyName(property))
                            .as(declaration)
                            .isTrue();
                }
            }
        });
    }

    @Test
    void nothingThrowsOnArbitraryText() {
        Properties.check("never throws", 2_000, Gens.strings(Gens.unicodeChars(), 0, 200), text -> {
            WP.post(text);
            BARE.post(text);
            WP.safeCss(text);
            WP.badProtocol(text);
            WP.oneAttr("a", text);
            Kses.stripAllTags(text, true);
            Kses.normalizeEntities(text);
        });
    }
}
