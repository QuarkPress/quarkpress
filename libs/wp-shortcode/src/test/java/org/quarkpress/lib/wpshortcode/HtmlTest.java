package org.quarkpress.lib.wpshortcode;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HtmlTest {

    private static final Shortcodes SHORTCODES =
            Shortcodes.builder(Registries.html()).build();

    private static void assertApply(String content, String expected) {
        assertThat(SHORTCODES.apply(content)).as(content).isEqualTo(expected);
    }

    private static void assertApplyIgnoringHtml(String content, String expected) {
        assertThat(SHORTCODES.apply(content, true)).as(content).isEqualTo(expected);
    }

    @Test
    void elementsWithOneKindOfBracketHideThem() { // R12.3
        assertApply("<p title=\"[\">[foo]</p>", "<p title=\"[\">{foo:[]:\"\"}</p>");
        assertApply("[foo]a < b[/foo]", "{foo:[]:\"\"}a < b[/foo]");
        assertApply("[foo]a<b[/foo]", "{foo:[]:\"\"}a<b[/foo]");
        assertApply("[foo]a < b > c[/foo]", "{foo:[]:\"a < b > c\"}");
        assertApply("[foo a=\"<b\"]", "[foo a=\"<b\"]");
        assertApply("[foo] x <", "{foo:[]:\"\"} x <");
        assertApply("x > [foo]", "x > {foo:[]:\"\"}");
        assertApply("<p title=\"]\">[foo]x[/foo]", "<p title=\"]\">{foo:[]:\"x\"}");
    }

    @Test
    void commentsAndCdataAreNeverExpanded() { // R12.4
        assertApply("<!-- [foo] -->[foo]", "<!-- [foo] -->{foo:[]:\"\"}");
        assertApply("<!--[if IE]>[foo]<![endif]-->", "<!--[if IE]>[foo]<![endif]-->");
        assertApply("<![CDATA[ [foo] ]]>", "<![CDATA[ [foo] ]]>");
        assertApply("<!-- [foo] [foo]", "<!-- [foo] [foo]");
        assertApply("<!--> [foo]", "<!--> {foo:[]:\"\"}");
        assertApply("<!---> [foo] -->[foo]", "<!---> {foo:[]:\"\"} -->{foo:[]:\"\"}");
    }

    @Test
    void ignoreHtmlLeavesMarkupAlone() { // R2.8, R12.4
        assertApplyIgnoringHtml("<a href=\"[url]\">x</a>", "<a href=\"[url]\">x</a>");
        assertApplyIgnoringHtml("<div>[foo]</div>", "<div>{foo:[]:\"\"}</div>");
        assertApplyIgnoringHtml("<[foo]>", "<[foo]>");
        assertApplyIgnoringHtml("<p class=[cls]>", "<p class=[cls]>");
    }

    @Test
    void elementsThatDoNotParse() { // R12.5a
        assertApply("<[foo]>", "<{foo:[]:\"\"}>");
        assertApply("< [foo]>", "< {foo:[]:\"\"}>");
        assertApply("[foo] <[foo]>", "{foo:[]:\"\"} <{foo:[]:\"\"}>");
        assertApply("</p class=\"[cls]\">", "</p class=\"[cls]\">");
        assertApply("<p 'bad'=\"[cls]\">", "<p 'bad'=\"[cls]\">");
        assertApply("<a title=\"[cls]>x</a>", "<a title=\"[cls]>x</a>");
        assertApply("<a title=[\"cls\"]>", "<a title=[\"cls\"]>");
        assertApply("x <- [foo]", "x <- [foo]");
        assertApply("<<[foo]>>", "<<[foo]>>");
        assertApply("x < y [foo] z", "x < y {foo:[]:\"\"} z");
        assertApply("<[[foo]]>", "<[foo]>");
        assertApply("<[foo] x=\"[cls]\">", "<{foo:[]:\"\"} x=\"red\">");
    }

    @Test
    void unquotedAttributesExpandRaw() { // R12.6a
        assertApply("<span [attr]>x</span>", "<span title=\"hi\">x</span>");
        assertApply("<a href=[url]>x</a>", "<a href=http://ex.com/?q=\"x\"&y=<z>>x</a>");
        assertApply("<p class=[cls] title=\"[cls]\">", "<p class=red title=\"red\">");
        assertApply("<p [foo]x[/foo]>", "<p [foo]x[/foo]>");
    }

    @Test
    void quotedAttributesGoThroughTheSanitizer() { // R12.6b
        assertApply("<p class=\"[cls]\">x</p>", "<p class=\"red\">x</p>");
        assertApply("<p class=\"[cls] [cls]\">", "<p class=\"red red\">");
        assertApply("<p class=\"[foo]x[/foo]\">t</p>", "<p class=\"{foo:[]:&quot;x&quot;}\">t</p>");
        assertApply("<a title=\"[foo]\" alt=\"[/foo]\">", "<a title=\"{foo:[]:&quot;&quot;}\" alt=\"[/foo]\">");
        assertApply(
                "<a title=\"[foo]x[/foo]\" class=\"[[cls]]\">", "<a title=\"{foo:[]:&quot;x&quot;}\" class=\"[cls]\">");
        assertApply("<p onclick=\"[cls]\">", "<p onclick=\"[cls]\">");
        assertApply("<custom class=\"[cls]\">", "<custom class=\"[cls]\">");
        assertApply("<p class=\"[nope]\">[foo]</p>", "<p class=\"[nope]\">{foo:[]:\"\"}</p>");
        assertApply("<p class=\"[foo]\">", "<p class=\"{foo:[]:&quot;&quot;}\">");
    }

    @Test
    void sanitizerEscapesAndChecksTheAllowlist() { // R12.7
        assertApply("<a href=\"[url]\">", "<a href=\"http://ex.com/?q=&quot;x&quot;&amp;y=&lt;z&gt;\">");
        assertApply("<a href='[url]'>", "<a href='http://ex.com/?q=&quot;x&quot;&amp;y=&lt;z&gt;'>");
        assertApply("<a href=\"[js]\">", "<a href=\"alert(1)\">");
        assertApply("<a title=\"[js]\">", "<a title=\"javascript:alert(1)\">");
        assertApply("<a title=\"a & b [cls] &amp; c\">", "<a title=\"a &amp; b red &amp; c\">");
        assertApply("<a title='x \"[cls]\"'>", "<a title='x &quot;red&quot;'>");
        assertApply("<a title = \"[cls]\">", "<a title=\"red\">");
        assertApply("<A TITLE=\"[cls]\">", "<A TITLE=\"red\">");
        assertApply("<p data-x=\"[cls]\">", "<p data-x=\"red\">");
        assertApply("<p data-X_1=\"[cls]\">", "<p data-X_1=\"red\">");
        assertApply("<img alt=\"[cls]\"/>", "<img alt=\"red\"/>");
        assertApply("<p class=\"[cls]\" / >", "<p class=\"red\" / >");
        assertApply("<a title=\"[cls]\"", "<a title=\"red\"");
        assertApply("<a download=\"[cls]\">", "<a download=\"[cls]\">");
        // WordPress: <p style="red"> (safecss_filter_attr); style is rejected here until lib-wp-kses (D4)
        assertApply("<p style=\"[cls]\">", "<p style=\"[cls]\">");
        assertApply("<a title=\"[ent]\">", "<a title=\"a &amp; b &copy; &amp;apos; &amp;bogus; &#065; &#x41;\">");
        assertApply("<a title=\"[nul]\">", "<a title=\"ab\">");
        assertApply("<a\ttitle=\"[cls]\"\n>", "<a\ttitle=\"red\"\n>");
        assertApply("<a title=\"[cls]\"x=\"[cls]\">", "<a title=\"red\"x=\"[cls]\">");
        assertApply("<object type=\"[cls]\">", "<object type=\"[cls]\">");
        assertApply(
                "<a rel=\"[cls]\" href=\"[js]\" target=\"_[cls]\">",
                "<a rel=\"red\" href=\"alert(1)\" target=\"_red\">");
    }
}
