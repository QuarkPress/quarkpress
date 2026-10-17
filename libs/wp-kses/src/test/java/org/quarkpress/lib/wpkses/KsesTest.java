package org.quarkpress.lib.wpkses;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class KsesTest {

    private static final Kses BARE = Kses.builder().preFilters(false).build();

    @Test
    void oneAttrMatchesWordPress() {
        Examples.assertFile("one-attr.json");
    }

    @Test
    void wrappersUseTheirContexts() {
        String in = "<a href=\"x\" title=\"t\" rel=\"r\" target=\"_blank\" class=\"c\">x</a><p>p</p>";
        assertThat(BARE.data(in)).isEqualTo("<a href=\"x\" title=\"t\">x</a>p");
        assertThat(BARE.post("<B title=\"T\" ONCLICK=\"x\">y</B>")).isEqualTo("<B title=\"T\">y</B>");
        assertThat(BARE.sanitize(in, KsesContext.USER_DESCRIPTION))
                .isEqualTo("<a href=\"x\" title=\"t\" rel=\"r\" target=\"_blank\">x</a>p");
    }

    @Test
    void anAllowedHtmlFilterAddingInputBringsForm() {
        // R11.2 (verified)
        Kses kses = Kses.builder()
                .preFilters(false)
                .allowedHtmlFilter((tags, context) -> context.equals(KsesContext.POST)
                        ? tags.toBuilder()
                                .attribute("input", "name")
                                .attribute("input", "type")
                                .build()
                        : tags)
                .build();
        assertThat(
                        kses.post(
                                "<form action=\"/s\" method=\"get\" onsubmit=\"x\"><input name=\"q\" type=\"text\" value=\"v\"></form>"))
                .isEqualTo("<form action=\"/s\" method=\"get\"><input name=\"q\" type=\"text\"></form>");
        assertThat(BARE.post("<form action=\"/x\"><input name=\"q\"></form>")).isEmpty();
    }

    @Test
    void protocolsAndUriAttributesAreConfigurable() {
        // R10.6 and R10.7 (verified)
        List<String> withJavascript = new ArrayList<>(Kses.wordPress().protocols());
        withJavascript.add("javascript");
        Kses js = Kses.builder().preFilters(false).protocols(withJavascript).build();
        assertThat(js.post("<a href=\"javascript:x\">a</a>")).isEqualTo("<a href=\"javascript:x\">a</a>");
        List<String> uris = new ArrayList<>(Kses.wordPress().uriAttributes());
        uris.add("data-href");
        Kses dataHref = Kses.builder().preFilters(false).uriAttributes(uris).build();
        assertThat(dataHref.post("<p data-href=\"javascript:x\" data-other=\"javascript:y\">p</p>"))
                .isEqualTo("<p data-href=\"x\" data-other=\"javascript:y\">p</p>");
    }

    @Test
    void preKsesFiltersSeeNormalizedContent() {
        // R4.1 (verified): the filter receives "aXb &amp;amp X<b>" in the data context.
        List<String> seen = new ArrayList<>();
        Kses kses = Kses.builder()
                .preFilters(false)
                .preFilter((content, allowed, protocols) -> {
                    seen.add(content);
                    return content.replace('X', 'Y');
                })
                .build();
        assertThat(kses.data("aXb &amp X<b>")).isEqualTo("aYb &amp;amp Y<b>");
        assertThat(seen).containsExactly("aXb &amp;amp X<b>");
    }

    @Test
    void explicitProtocolsApplyToAttributesButNotToCss() {
        // R10.9 (verified)
        assertThat(BARE.sanitize(
                        "<a href=\"http://x\">a</a><p style=\"background:url(http://y/a.png)\">p</p><a href=\"mailto:a\">m</a>",
                        KsesContext.POST,
                        List.of("https")))
                .isEqualTo("<a href=\"//x\">a</a><p style=\"background:url(http://y/a.png)\">p</p><a href=\"a\">m</a>");
    }

    @Test
    void pdfObjectsNeedAnUploadUrl() {
        String object =
                "<object data=\"https://example.org/wp-content/uploads/2026/10/a.pdf\" type=\"application/pdf\"></object>";
        assertThat(Kses.builder()
                        .preFilters(false)
                        .uploadBaseUrl(Examples.UPLOAD_URL)
                        .build()
                        .post(object))
                .isEqualTo(object);
        assertThat(BARE.post(object)).isEqualTo("<object></object>");
    }

    @Test
    void oneAttrIsEmptyWhenWordPressReturnsOnlyWhitespace() {
        assertThat(BARE.oneAttr("a", "href=\"javascript:x\"")).contains("href=\"x\"");
        assertThat(BARE.oneAttr("a", " onclick=\"x\" ")).isEmpty();
        assertThat(BARE.oneAttr("a", "title=\"unterminated")).isEmpty();
        assertThat(BARE.oneAttr("a", " title=\"a<b\" ")).contains(" title=\"a&lt;b\" ");
        assertThat(BARE.oneAttr("script", "title=\"x\"")).isEqualTo(Optional.empty());
    }

    @Test
    void oneAttrFitsTheShortcodeSanitizerShape() {
        java.util.function.BiFunction<String, String, Optional<String>> sanitizer = BARE::oneAttr;
        assertThat(sanitizer.apply("a", "style=\"color:red;foo:bar\"")).contains("style=\"color:red\"");
    }

    @Test
    void nullArgumentsThrow() {
        assertThatThrownBy(() -> BARE.post(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> BARE.sanitize("x", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> BARE.sanitize("x", KsesContext.POST, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> BARE.oneAttr(null, "x")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> BARE.badProtocol(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> BARE.safeCss(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Kses.normalizeEntities(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void loneSurrogatesBecomeReplacementCharacters() {
        assertThat(BARE.post("<b title=\"a" + (char) 0xDC00 + "\">x" + (char) 0xD800 + "</b>"))
                .isEqualTo("<b title=\"a" + TextUtils.REPLACEMENT_CHARACTER + "\">x" + TextUtils.REPLACEMENT_CHARACTER
                        + "</b>");
    }

    @Test
    void filtersThatReturnNullAreReported() {
        // A broken host filter fails loudly where it is configured or used.
        assertThatThrownBy(() -> Kses.builder()
                        .allowedHtmlFilter((tags, context) -> null)
                        .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("AllowedHtmlFilter");
        Kses broken = Kses.builder()
                .preFilters(false)
                .preFilter((content, allowed, protocols) -> null)
                .build();
        assertThatThrownBy(() -> broken.post("x"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("PreKsesFilter");
        IllegalStateException boom = new IllegalStateException("boom");
        Kses throwing = Kses.builder()
                .preFilters(false)
                .cssAllowFilter((allowed, test) -> {
                    throw boom;
                })
                .build();
        assertThatThrownBy(() -> throwing.safeCss("color:red")).isSameAs(boom);
    }

    @Test
    void oneInstanceIsSafeToShareBetweenThreads() throws Exception {
        // One shared instance must give the same answers from several threads.
        Kses kses = Kses.wordPress();
        List<String> inputs = List.of(
                "<p style=\"color:red;foo:bar\" onclick=\"x\">a</p>",
                "<a href=\"javascript:x\">b</a>",
                "<!-- wp:p {\"a\":\"\\u003cscript\\u003e\"} /-->",
                "a < b <!-- c --> <img src=x onerror=y>");
        List<String> expected = inputs.stream().map(kses::post).toList();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                results.add(pool.submit(() -> {
                    for (int i = 0; i < 2_000; i++) {
                        int k = i % inputs.size();
                        if (!kses.post(inputs.get(k)).equals(expected.get(k))) {
                            return false;
                        }
                    }
                    return true;
                }));
            }
            for (Future<Boolean> result : results) {
                assertThat(result.get()).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void tablesAreExposed() {
        Kses kses = Kses.wordPress();
        assertThat(kses.protocols()).hasSize(22);
        assertThat(kses.uriAttributes()).hasSize(17);
        assertThat(kses.safeStyleProperties()).hasSize(191);
        assertThat(kses.allowedHtml(KsesContext.PRE_COMMENT_CONTENT).allows("span"))
                .isTrue();
        assertThat(BARE.allowedHtml(KsesContext.PRE_COMMENT_CONTENT).allows("span"))
                .isFalse();
        assertThat(kses.allowedHtml(KsesContext.named("anything"))).isEqualTo(kses.allowedHtml(KsesContext.DATA));
    }

    @Test
    void uploadBaseUrlIsValidatedWhenSet() {
        assertThatThrownBy(() -> Kses.builder().uploadBaseUrl("http://exa mple.org/u"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uploadBaseUrl");
    }

    @Test
    void aHostJavaCannotParseFailsClosedForPdfObjects() {
        String object = "<object data=\"https://my_host/u/a.pdf\" type=\"application/pdf\"></object>";
        assertThat(Kses.builder().uploadBaseUrl("https://my_host/u").build().post(object))
                .isEqualTo("<object></object>");
    }

    @Test
    void wordPressModeIsStableOnTheNonIdempotentBareCase() {
        Kses wordPress = Kses.wordPress();
        String once = wordPress.post("<!a<b>>");
        assertThat(wordPress.post(once)).isEqualTo(once);
        String bareOnce = BARE.post("<!a<b>>");
        assertThat(BARE.post(bareOnce)).isNotEqualTo(bareOnce);
    }

    @Test
    void emptySafeStylePropertiesKeepEveryDeclaration() {
        Kses open = Kses.builder().safeStyleProperties(List.of()).build();
        assertThat(open.safeCss("position:fixed; color:red")).isEqualTo("position:fixed; color:red");
    }
}
