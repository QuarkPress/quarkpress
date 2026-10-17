package org.quarkpress.lib.wxr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

class XmlTextTest {

    private static String apply(BiConsumer<StringBuilder, String> f, String value) {
        StringBuilder out = new StringBuilder();
        f.accept(out, value);
        return out.toString();
    }

    @Test
    void sanitizeReplacesWhatXmlCannotHold() {
        assertThat(XmlText.sanitize("a\u0001b\uD800c\uFFFEd\uD83D\uDE00\t\n"))
                .isEqualTo("a\uFFFDb\uFFFDc\uFFFDd\uD83D\uDE00\t\n");
        String clean = "plain";
        assertThat(XmlText.sanitize(clean)).isSameAs(clean);
    }

    @Test
    void cdataSplitsLikeWordPress() {
        // R20 / Example A line 364: x[y[0]]> z is written as x[y[0]]]]><![CDATA[> z.
        assertThat(apply(XmlText::cdata, "<p>Use x[y[0]]> z</p>"))
                .isEqualTo("<![CDATA[<p>Use x[y[0]]]]><![CDATA[> z</p>]]>");
        assertThat(apply(XmlText::cdata, null)).isEqualTo("<![CDATA[]]>");
        assertThat(apply(XmlText::cdata, "a]]")).isEqualTo("<![CDATA[a]]]]>");
    }

    @Test
    void cdataKeepsCarriageReturnsAsReferences() {
        assertThat(apply(XmlText::cdata, "a\r\nb")).isEqualTo("<![CDATA[a]]>&#13;<![CDATA[\nb]]>");
    }

    @Test
    void textAndAttributesAreEscaped() {
        assertThat(apply(XmlText::text, "a&b<c>d\re")).isEqualTo("a&amp;b&lt;c&gt;d&#13;e");
        assertThat(apply(XmlText::attribute, "a\"b&c<d\te\nf")).isEqualTo("a&quot;b&amp;c&lt;d&#9;e&#10;f");
    }
}
