package org.quarkpress.lib.wpshortcode;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HtmlElementTest {

    private static void assertParsed(String element, String front, String name, String back, String... attributes) {
        HtmlElement.Parsed parsed = HtmlElement.parse(element);
        assertThat(parsed).as(element).isNotNull();
        assertThat(parsed.front()).as("front of %s", element).isEqualTo(front);
        assertThat(parsed.name()).as("name of %s", element).isEqualTo(name);
        assertThat(parsed.back()).as("back of %s", element).isEqualTo(back);
        assertThat(parsed.attributes()).as("attributes of %s", element).containsExactly(attributes);
        assertThat(front + String.join("", parsed.attributes()) + back).isEqualTo(element);
    }

    private static void assertSplit(String text, String... pieces) {
        List<String> actual = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int lt = text.indexOf('<', i);
            if (lt < 0) {
                actual.add(text.substring(i));
                break;
            }
            if (lt > i) {
                actual.add(text.substring(i, lt));
            }
            int end = HtmlElement.end(text, lt);
            actual.add(text.substring(lt, end));
            i = end;
        }
        assertThat(actual).as(text).containsExactly(pieces);
    }

    @Test
    void parsesElementsLikeKsesAttributeParse() { // R12.5
        assertParsed("<a href=\"x\" title='y'>", "<a ", "a", ">", "href=\"x\" ", "title='y'");
        assertParsed("<a>", "<a", "a", ">");
        assertParsed("<a", "<a", "a", "");
        assertParsed("< a  b >", "< a  ", "a", ">", "b ");
        assertThat(HtmlElement.parse("</a>")).isNull();
        assertThat(HtmlElement.parse("< /a>")).isNull();
        assertParsed("<1x y=z>", "<1x ", "1x", ">", "y=z");
        assertParsed("<a [x] y=[z]>", "<a ", "a", ">", "[x] ", "y=[z]");
        assertParsed("<a [[x]]=1>", "<a ", "a", ">", "[[x]]=1");
        assertThat(HtmlElement.parse("<a [x]y>")).isNull();
        assertParsed("<a title=\"[x]\"y=\"z\">", "<a ", "a", ">", "title=\"[x]\"", "y=\"z\"");
        assertParsed("<img src=\"a\" />", "<img ", "img", " />", "src=\"a\"");
        assertParsed("<img src=\"a\"/>", "<img ", "img", "/>", "src=\"a\"");
        assertParsed("<p\tclass=a\n>", "<p\t", "p", ">", "class=a\n");
        assertThat(HtmlElement.parse("<a title=x\"y>")).isNull();
        assertThat(HtmlElement.parse("<a title=\"x>")).isNull();
        assertThat(HtmlElement.parse("<a =x>")).isNull();
        assertParsed("<a b = c>", "<a ", "a", ">", "b = c");
        assertThat(HtmlElement.parse("<a b=>")).isNull();
        assertThat(HtmlElement.parse("<a b= >")).isNull();
        assertParsed("<a b c>", "<a ", "a", ">", "b ", "c");
        assertThat(HtmlElement.parse("<a b=\"c\" / d>")).isNull();
        assertParsed("<a _x:y.z-1=2>", "<a ", "a", ">", "_x:y.z-1=2");
        assertThat(HtmlElement.parse("<a -x>")).isNull();
        assertParsed("<a [ ]>", "<a ", "a", ">", "[ ]");
        assertThat(HtmlElement.parse("<a []>")).isNull();
        assertParsed("<a /  >", "<a ", "a", "/  >");
        assertParsed("<a b=\"1\"c='2'd>", "<a ", "a", ">", "b=\"1\"", "c='2'", "d");
        assertParsed("<ab12 x>", "<ab12 ", "ab12", ">", "x");
        assertThat(HtmlElement.parse("<a b=c\"d>")).isNull();
    }

    @Test
    void elementsEndLikeWpHtmlSplit() { // R12.2
        assertSplit("a<b>c", "a", "<b>", "c");
        assertSplit("<!-- x --> y", "<!-- x -->", " y");
        assertSplit("<!-->z", "<!-->", "z");
        assertSplit("<!--->z", "<!--->", "z");
        assertSplit("<!-- open", "<!-- open");
        assertSplit("<![CDATA[ x ]]> y", "<![CDATA[ x ]]>", " y");
        assertSplit("<![CDATA[ x ]]]> y", "<![CDATA[ x ]]]>", " y");
        assertSplit("<![CDATA[ open", "<![CDATA[ open");
        assertSplit("<!x>y", "<!x>", "y");
        assertSplit("<p", "<p");
        assertSplit("a < b > c", "a ", "< b >", " c");
        assertSplit("<<a>>", "<<a>", ">");
    }

    @Test
    void recognizesTheBracketedNamePluginPattern() { // R12.5a
        assertThat(HtmlElement.startsWithBracketedName("<[email]>")).isTrue();
        assertThat(HtmlElement.startsWithBracketedName("< \t[[x]")).isTrue();
        assertThat(HtmlElement.startsWithBracketedName("<[]>")).isFalse();
        assertThat(HtmlElement.startsWithBracketedName("<[x")).isFalse();
        assertThat(HtmlElement.startsWithBracketedName("<p [x]>")).isFalse();
        assertThat(HtmlElement.bracketedName("[[x]] y", 0)).isEqualTo(5);
        assertThat(HtmlElement.bracketedName("[x]]", 0)).isEqualTo(4);
        assertThat(HtmlElement.bracketedName("[[]x]", 0)).isEqualTo(-1);
    }
}
