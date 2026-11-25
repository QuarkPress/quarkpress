package org.quarkword.front.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.quarkword.front.FrontFlags;

/**
 * The numeric and hostile query values WordPress's {@code parse_query} normalises: PHP's integer cast of a string
 * (leading whitespace, a sign, digits, a fraction, an exponent; saturating), its absolute value, and the bounds on the
 * search term. Every value recorded with PHP 8.4 (parse-query.out, "PHP integer casts").
 */
class QueryVarsTest {

    private static final FrontFlags SINGLE = FrontFlags.none().withSingle(true).withSingular(true);

    private static String p(String value) {
        return FlagParserTest.parse("p=" + value).normalized().get("p").orElseThrow();
    }

    private static String pageId(String value) {
        return FlagParserTest.parse("page_id=" + value)
                .normalized()
                .get("page_id")
                .orElseThrow();
    }

    @Test
    void numericEdges() {
        assertThat(p("10abc")).isEqualTo("10");
        assertThat(p("%2012")).isEqualTo("12");
        assertThat(p("%0A12")).isEqualTo("12");
        assertThat(p("12%20abc")).isEqualTo("12");
        assertThat(p("%2B5")).isEqualTo("5");
        assertThat(p("1e3")).isEqualTo("1000");
        assertThat(p("1.5e1")).isEqualTo("15");
        assertThat(p(".5e1")).isEqualTo("5");
        assertThat(p("1.9")).isEqualTo("1");
        assertThat(p("5.")).isEqualTo("5");
        assertThat(p(".5")).isEqualTo("0");
        assertThat(p("0x1A")).isEqualTo("0");
        assertThat(p("0b11")).isEqualTo("0");
        assertThat(p("007")).isEqualTo("7");
        assertThat(p("1_000")).isEqualTo("1");
        assertThat(p("1,5")).isEqualTo("1");
        assertThat(p("1e")).isEqualTo("1");
        assertThat(p("1e%2B")).isEqualTo("1");
        assertThat(p("2e0x")).isEqualTo("2");
        assertThat(p("1e-2")).isEqualTo("0");
        assertThat(p("1e400")).isEqualTo("0");
        assertThat(p("--1")).isEqualTo("0");
        assertThat(p("-0")).isEqualTo("0");
        assertThat(p("abc")).isEqualTo("0");
        assertThat(p("%D9%A3")).isEqualTo("0");
        assertThat(p("9223372036854775807")).isEqualTo("9223372036854775807");
        assertThat(p("9223372036854775808")).isEqualTo("9223372036854775807");
        assertThat(p("9223372036854775807.5")).isEqualTo("9223372036854775807");
        assertThat(p("1e19")).isEqualTo("9223372036854775807");
        assertThat(p("9.3e18")).isEqualTo("9223372036854775807");
        // a digit string beyond the double range is infinite in PHP and casts to 0, shorter ones saturate
        assertThat(p("9".repeat(20))).isEqualTo("9223372036854775807");
        assertThat(p("9".repeat(308))).isEqualTo("9223372036854775807");
        assertThat(p("1" + "0".repeat(308))).isEqualTo("9223372036854775807");
        assertThat(p("9".repeat(309))).isEqualTo("0");
        assertThat(p("1" + "0".repeat(309))).isEqualTo("0");
        assertThat(p("9".repeat(400))).isEqualTo("0");
        assertThat(p("0".repeat(401) + "5")).isEqualTo("5");
        assertThat(p("9".repeat(400) + ".5")).isEqualTo("0");
        assertThat(p("1e308")).isEqualTo("9223372036854775807");
        assertThat(p("1e309")).isEqualTo("0");
        assertThat(FlagParserTest.flags("p=" + "9".repeat(400)))
                .isEqualTo(FrontFlags.none().withHome(true));
        assertThat(FlagParserTest.flags("p=-" + "9".repeat(400)))
                .isEqualTo(FrontFlags.none().withHome(true));
        assertThat(FlagParserTest.flags("paged=" + "9".repeat(400)))
                .isEqualTo(FrontFlags.none().withHome(true));
        assertThat(pageId("0".repeat(400) + "5")).isEqualTo("5");
        // the absolute value saturates too
        assertThat(pageId("-20")).isEqualTo("20");
        assertThat(pageId("-9223372036854775808")).isEqualTo("9223372036854775807");
        assertThat(pageId("-9223372036854775809")).isEqualTo("9223372036854775807");
        assertThat(pageId("-1e19")).isEqualTo("9223372036854775807");
        assertThat(pageId("1e400")).isEqualTo("0");
        assertThat(pageId("%202x")).isEqualTo("2");
    }

    @Test
    void hostileValues() {
        String longSearch = "a".repeat(1601);
        FlagParser.Parsed tooLong = FlagParserTest.parse("s=" + longSearch);
        assertThat(tooLong.normalized().get("s")).contains("");
        assertThat(tooLong.flags()).isEqualTo(FrontFlags.none().withSearch(true));
        FlagParser.Parsed longest = FlagParserTest.parse("s=" + "a".repeat(1600));
        assertThat(longest.normalized().get("s")).contains("a".repeat(1600));
        // bytes, not characters: 801 two-byte characters are too long
        assertThat(FlagParserTest.parse("s=" + "%C3%A9".repeat(801))
                        .normalized()
                        .get("s"))
                .contains("");
        assertThat(FlagParserTest.parse("s=" + "%C3%A9".repeat(800))
                        .normalized()
                        .get("s"))
                .contains("é".repeat(800));
        FlagParser.Parsed negative = FlagParserTest.parse("p=-1");
        assertThat(negative.flags()).isEqualTo(FrontFlags.notFound());
        assertThat(negative.normalized().asMap()).isEqualTo(Map.of("p", "0", "error", "404"));
        assertThat(FlagParserTest.parse("p=-1e19").flags()).isEqualTo(FrontFlags.notFound());
        FlagParser.Parsed huge = FlagParserTest.parse("p=9223372036854775808");
        assertThat(huge.flags()).isEqualTo(SINGLE);
        assertThat(huge.normalized().get("p")).contains("9223372036854775807");
        assertThat(FlagParserTest.parse("page=%2F2%2F&p=10").normalized().get("page"))
                .contains("2");
        assertThat(FlagParserTest.parse("page=2x&p=10").normalized().get("page"))
                .contains("2");
        assertThat(FlagParserTest.parse("page=abc").normalized().get("page")).contains("0");
        assertThat(FlagParserTest.parse("m=2026052008xx").normalized().get("m")).contains("2026052008");
        assertThat(FlagParserTest.parse("m=%E2%82%AC2026").normalized().get("m"))
                .contains("2026");
        assertThat(FlagParserTest.parse("cat=a3b").normalized().get("cat")).contains("3");
        assertThat(FlagParserTest.parse("cat=3%204").normalized().get("cat")).contains("34");
        assertThat(FlagParserTest.parse("author=3x,-2y").normalized().get("author"))
                .contains("3,-2");
        assertThat(FlagParserTest.parse("name=%09hello%00").normalized().get("name"))
                .contains("hello");
        assertThat(FlagParserTest.parse("name=%E2%80%83hello").normalized().get("name"))
                .contains(" hello");
        assertThat(FlagParserTest.parse("post_type=%3Cscript%3E").normalized().get("post_type"))
                .contains("script");
        assertThat(FlagParserTest.parse("error=404&robots=1").flags()).isEqualTo(FrontFlags.notFound());
    }
}
