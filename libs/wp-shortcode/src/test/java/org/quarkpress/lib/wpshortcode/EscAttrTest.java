package org.quarkpress.lib.wpshortcode;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EscAttrTest {

    private static void assertEscAttr(String text, String expected) {
        assertThat(EscAttr.escape(text)).as(text).isEqualTo(expected);
    }

    @Test
    void escapesLikeWordPress() {
        assertEscAttr("", "");
        assertEscAttr("plain", "plain");
        assertEscAttr("a & b", "a &amp; b");
        assertEscAttr("&amp;", "&amp;");
        assertEscAttr("&amp;amp;", "&amp;amp;");
        assertEscAttr("<b> \"q\" 's'", "&lt;b&gt; &quot;q&quot; &#039;s&#039;");
        assertEscAttr("&#91;", "&#091;");
        assertEscAttr("&#0065;", "&#065;");
        assertEscAttr("&#65", "&amp;#65");
        assertEscAttr("&#x41;", "&#x41;");
        assertEscAttr("&#X41;", "&#x41;");
        assertEscAttr("&#x0041;", "&#x41;");
        assertEscAttr("&#1114112;", "&amp;#1114112;");
        assertEscAttr("&#x110000;", "&amp;#x110000;");
        assertEscAttr("&#xD800;", "&amp;#xD800;");
        assertEscAttr("&#0;", "&amp;#0;");
        assertEscAttr("&#1;", "&amp;#1;");
        assertEscAttr("&#9;", "&#009;");
        assertEscAttr("&#12345678;", "&amp;#12345678;");
        assertEscAttr("&#00000000065;", "&#065;");
        assertEscAttr("&foo;", "&amp;foo;");
        assertEscAttr("&apos;", "&amp;apos;");
        assertEscAttr("&nbsp;", "&nbsp;");
        assertEscAttr("&hellip;", "&hellip;");
        assertEscAttr("&sup2;", "&sup2;");
        assertEscAttr("&frac14;", "&frac14;");
        assertEscAttr("&abcdefghi;", "&amp;abcdefghi;");
        assertEscAttr("&ab;", "&amp;ab;");
        assertEscAttr("&a;", "&amp;a;");
        assertEscAttr("&#x;", "&amp;#x;");
        assertEscAttr("&#;", "&amp;#;");
        assertEscAttr("&&amp;", "&amp;&amp;");
        assertEscAttr("&#38;#65;", "&#038;#65;");
        assertEscAttr("&#X0;", "&amp;#X0;");
        assertEscAttr("&#xFFFE;", "&amp;#xFFFE;");
        assertEscAttr("&#65534;", "&amp;#65534;");
        assertEscAttr("&copy", "&amp;copy");
        assertEscAttr("&Alpha;", "&Alpha;");
        assertEscAttr("&alpha;", "&alpha;");
        assertEscAttr("&ALPHA;", "&amp;ALPHA;");
    }

    @Test
    void invalidUtf16BecomesEmpty() { // esc_attr returns "" for invalid UTF-8
        assertEscAttr("a\uD800b", "");
    }
}
