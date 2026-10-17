package org.quarkpress.lib.wpshortcode;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class HtmlProtectorTest {

    private static final ShortcodeScanner SCANNER = new ShortcodeScanner(List.of("x"));
    private static final Function<String, ShortcodeScanner.Expansion> EXPANDER = text -> SCANNER.replace(text, hit -> {
        ShortcodeMatch m = hit.match();
        return m.escaped() ? m.whole().substring(1, m.whole().length() - 1) : m.openEscape() + "X\"<" + m.closeEscape();
    });

    private static void assertProtected(boolean ignoreHtml, String content, String expected) {
        String actual = HtmlProtector.protect(
                EntityCodec.normalize(content), ignoreHtml, EXPANDER, BasicAttributeSanitizer.INSTANCE);
        assertThat(actual).as(content).isEqualTo(expected);
    }

    @Test
    void protectsLikeDoShortcodesInHtmlTags() {
        assertProtected(false, "a [x] <b>[x]</b>", "a [x] <b>[x]</b>");
        assertProtected(false, "<p title=\"[x]\">", "<p title=\"X&quot;&lt;\">");
        assertProtected(false, "<p title=[x]>", "<p title=X\"<>");
        assertProtected(false, "<p title=\"[y]\">", "<p title=\"&#91;y&#93;\">");
        assertProtected(false, "<!-- [x] -->", "<!-- &#91;x&#93; -->");
        assertProtected(false, "<p title=\"[\">", "<p title=\"&#91;\">");
        assertProtected(false, "<p title=\"]\">", "<p title=\"&#93;\">");
        assertProtected(false, "<[x]>", "<X\"<>");
        assertProtected(false, "</p [x]>", "</p &#91;x&#93;>");
        assertProtected(true, "<p title=\"[x]\">", "<p title=\"&#91;x&#93;\">");
        assertProtected(true, "<p title=\"[\">", "<p title=\"&#91;\">");
        assertProtected(false, "&#91;x&#93; <i>", "&#091;x&#093; <i>");
        assertProtected(false, "<p title=\"[x]\" class='[x]'>[x]", "<p title=\"X&quot;&lt;\" class='X&quot;&lt;'>[x]");
    }

    @Test
    void aBlankSanitizerResultKeepsTheOriginalToken() {
        HtmlAttributeSanitizer blank = (element, token) -> Optional.of(" \t");
        String actual = HtmlProtector.protect("<p title=\"[x]\">", false, EXPANDER, blank);
        assertThat(actual).isEqualTo("<p title=\"&#91;x&#93;\">");
    }

    @Test
    void textWithoutElementsIsReturnedAsIs() {
        String text = "[x] plain";
        assertThat(HtmlProtector.protect(text, false, EXPANDER, BasicAttributeSanitizer.INSTANCE))
                .isSameAs(text);
    }

    @Test
    void anAttributeTheSanitizerRejectsStaysAsWrittenWithEncodedBrackets() {
        assertProtected(false, "<p onclick=\"[x]\">", "<p onclick=\"&#91;x&#93;\">");
        assertProtected(false, "<a title=\"[x]\" y=\"[x]\">", "<a title=\"X&quot;&lt;\" y=\"&#91;x&#93;\">");
    }

    @Test
    void attributesWithoutSeparatingWhitespaceAreHandledLikeWordPress() {
        // WordPress expands only the first token of <a title="[foo]"y="[foo]">
        assertProtected(false, "<a title=\"[x]\"y=\"[x]\">", "<a title=\"X&quot;&lt;\"y=\"&#91;x&#93;\">");
    }

    @Test
    void closingBracketAfterAShortcodeInsideAQuotedValueIsEncoded() {
        assertProtected(false, "<p title=\"[x]]\">", "<p title=\"X&quot;&lt;&#93;\">");
        assertProtected(false, "<p class=\"a\" id=[x]>", "<p class=\"a\" id=X\"<>");
    }
}
