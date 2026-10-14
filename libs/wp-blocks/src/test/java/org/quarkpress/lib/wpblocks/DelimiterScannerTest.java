package org.quarkpress.lib.wpblocks;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.quarkpress.lib.wpblocks.DelimiterScanner.Delimiter;
import org.quarkpress.lib.wpblocks.DelimiterScanner.Kind;

class DelimiterScannerTest {

    @Test
    void readsOpenerWithoutAttributes() {
        Delimiter d = DelimiterScanner.tryRead("<!-- wp:paragraph -->", 0);
        assertThat(d.kind()).isEqualTo(Kind.OPENER);
        assertThat(d.name()).isEqualTo("core/paragraph");
        assertThat(d.rawAttributes()).isNull();
        assertThat(d.start()).isZero();
        assertThat(d.end()).isEqualTo(21);
    }

    @Test
    void readsOpenerWithAttributesAndKeepsRawText() {
        String s = "xx<!-- wp:acme/hero {\"a\": 1,  \"b\":[1,2]} -->yy";
        Delimiter d = DelimiterScanner.tryRead(s, 2);
        assertThat(d.kind()).isEqualTo(Kind.OPENER);
        assertThat(d.name()).isEqualTo("acme/hero");
        assertThat(d.rawAttributes()).isEqualTo("{\"a\": 1,  \"b\":[1,2]}");
        assertThat(s.substring(d.start(), d.end())).isEqualTo("<!-- wp:acme/hero {\"a\": 1,  \"b\":[1,2]} -->");
    }

    @Test
    void readsVoidAndCloser() {
        Delimiter v = DelimiterScanner.tryRead("<!-- wp:spacer {\"height\":10} /-->", 0);
        assertThat(v.kind()).isEqualTo(Kind.VOID);
        assertThat(v.rawAttributes()).isEqualTo("{\"height\":10}");
        Delimiter c = DelimiterScanner.tryRead("<!-- /wp:core/group -->", 0);
        assertThat(c.kind()).isEqualTo(Kind.CLOSER);
        assertThat(c.name()).isEqualTo("core/group");
        assertThat(c.rawAttributes()).isNull();
    }

    @Test
    void acceptsAnyWhitespaceMix() {
        assertThat(DelimiterScanner.tryRead("<!--\twp:a\n{\"x\":1}\r\n/-->", 0).kind())
                .isEqualTo(Kind.VOID);
        assertThat(DelimiterScanner.tryRead("<!--   /wp:a\t\t-->", 0).kind()).isEqualTo(Kind.CLOSER);
    }

    @Test
    void attributeBracesInsideStringsAreSkipped() {
        String s = "<!-- wp:a {\"t\":\"}\",\"u\":\"\\\"}\\\"\",\"v\":\"-->\",\"w\":{\"x\":\"{\"}} -->";
        Delimiter d = DelimiterScanner.tryRead(s, 0);
        assertThat(d).isNotNull();
        assertThat(d.rawAttributes()).isEqualTo("{\"t\":\"}\",\"u\":\"\\\"}\\\"\",\"v\":\"-->\",\"w\":{\"x\":\"{\"}}");
        assertThat(d.end()).isEqualTo(s.length());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "<!--wp:paragraph -->", // no whitespace after <!--
                "<!-- wp:paragraph-->", // no whitespace before -->
                "<!-- wp:Paragraph -->", // uppercase
                "<!-- wp:core/4-invalid /-->", // digit-first segment
                "<!-- wp:paragraph {\"a\":1}-->", // no whitespace after attributes
                "<!-- wp:paragraph {\"a\":1 -->", // unbalanced
                "<!-- wp:paragraph [1] -->", // not an object
                "<!-- wp: paragraph -->", // space after colon
                "<!-- wp:paragraph", // unterminated
                "<!-- /wp:paragraph {\"a\":1} -->", // closers take no attributes
                "<!-- /wp:paragraph /-->", // closers are not void
                "<!-- wp -->", // no colon
                "<!---->",
                "<!-- -->",
                "<!-- more -->"
            })
    void rejectsNearMisses(String s) {
        assertThat(DelimiterScanner.tryRead(s, 0)).isNull();
    }

    @Test
    void requiresCandidateAtStartIndex() {
        assertThat(DelimiterScanner.tryRead("x<!-- wp:a -->", 0)).isNull();
        assertThat(DelimiterScanner.tryRead("x<!-- wp:a -->", 1)).isNotNull();
    }

    @Test
    void terminatorIsFirstBraceFollowedByWhitespaceAndClose() {
        assertThat(new AttributeEnds("{\"a\":{").terminatorFrom(0)).isEqualTo(-1);
        assertThat(new AttributeEnds("{} -->").terminatorFrom(0)).isEqualTo(1);
        assertThat(new AttributeEnds("{}-->").terminatorFrom(0)).isEqualTo(-1);
        assertThat(new AttributeEnds("ab{\"q\":\"}\"} /-->").terminatorFrom(2)).isEqualTo(10);
        AttributeEnds memo = new AttributeEnds("{} --> {} -->");
        assertThat(memo.terminatorFrom(0)).isEqualTo(1);
        assertThat(memo.terminatorFrom(1)).isEqualTo(1);
        assertThat(memo.terminatorFrom(2)).isEqualTo(8);
        assertThat(memo.terminatorFrom(9)).isEqualTo(-1);
    }

    @Test
    void attributeEndsAtFirstBraceBeforeCloseEvenInsideString() {
        String doc = "<!-- wp:a {\"a\":\"} -->\"} -->";
        Delimiter d = DelimiterScanner.tryRead(doc, 0);
        assertThat(d.kind()).isEqualTo(Kind.OPENER);
        assertThat(d.rawAttributes()).isEqualTo("{\"a\":\"}");
        assertThat(d.end()).isEqualTo(doc.indexOf("-->") + 3);
    }

    @Test
    void attributesNeedWhitespaceBeforeCloseButAnyWhitespaceWorks() {
        assertThat(DelimiterScanner.tryRead("<!-- wp:a {}/-->", 0)).isNull();
        Delimiter d = DelimiterScanner.tryRead("<!-- wp:a {\"a\":1}\n\t/-->", 0);
        assertThat(d.kind()).isEqualTo(Kind.VOID);
        assertThat(d.rawAttributes()).isEqualTo("{\"a\":1}");
    }
}
