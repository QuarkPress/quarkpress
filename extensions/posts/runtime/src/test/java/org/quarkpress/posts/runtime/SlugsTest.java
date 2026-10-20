package org.quarkpress.posts.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.SiteId;
import org.quarkpress.posts.SlugContext;
import org.quarkpress.testing.gen.Gens;
import org.quarkpress.testing.gen.Properties;

class SlugsTest {

    private static String cp(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    @Test
    void titlesBecomeWordPressSlugs() {
        assertThat(Slugs.sanitizeTitle("Hello World!")).isEqualTo("hello-world");
        assertThat(Slugs.sanitizeTitle("Caf" + cp(0xE9) + " cr" + cp(0xE8) + "me"))
                .isEqualTo("cafe-creme");
        assertThat(Slugs.sanitizeTitle(
                        cp(0xDC) + "n" + cp(0xEF) + "c" + cp(0xF6) + "d" + cp(0xE9) + " " + cp(0x2014) + " test"))
                .isEqualTo("unicode-test");
        assertThat(Slugs.sanitizeTitle("  --Hello--  ")).isEqualTo("hello");
        assertThat(Slugs.sanitizeTitle(cp(0x41F, 0x440, 0x438, 0x432, 0x435, 0x442) + " " + cp(0x43C, 0x438, 0x440)))
                .isEqualTo("%d0%bf%d1%80%d0%b8%d0%b2%d0%b5%d1%82-%d0%bc%d0%b8%d1%80");
        assertThat(Slugs.sanitizeTitle(cp(0x65E5, 0x672C, 0x8A9E, 0x306E, 0x30BF, 0x30A4, 0x30C8, 0x30EB)))
                .isEqualTo("%e6%97%a5%e6%9c%ac%e8%aa%9e%e3%81%ae%e3%82%bf%e3%82%a4%e3%83%88%e3%83%ab");
        assertThat(Slugs.sanitizeTitle("100% done")).isEqualTo("100-done");
        assertThat(Slugs.sanitizeTitle("a/b/c")).isEqualTo("a-b-c");
        assertThat(Slugs.sanitizeTitle("x.y.z")).isEqualTo("x-y-z");
        assertThat(Slugs.sanitizeTitle("<b>Bold</b> title")).isEqualTo("bold-title");
        assertThat(Slugs.sanitizeTitle("Rock &amp; Roll")).isEqualTo("rock-roll");
        assertThat(Slugs.sanitizeTitle("Hello&nbsp;World")).isEqualTo("hello-world");
        assertThat(Slugs.sanitizeTitle("Don" + cp(0x2019) + "t stop")).isEqualTo("dont-stop");
        assertThat(Slugs.sanitizeTitle("2 " + cp(0xD7) + " 3")).isEqualTo("2-x-3");
        assertThat(Slugs.sanitizeTitle(cp(0xDF) + " stra" + cp(0xDF) + "e")).isEqualTo("s-strase");
        assertThat(Slugs.sanitizeTitle("%E6%97%A5")).isEqualTo("%e6%97%a5");
        assertThat(Slugs.sanitizeTitle("foo%zz")).isEqualTo("foozz");
        assertThat(Slugs.sanitizeTitle("__trashed")).isEqualTo("__trashed");
        assertThat(Slugs.sanitizeTitle(cp(0x1F389) + " Party")).isEqualTo("%f0%9f%8e%89-party");
        assertThat(Slugs.sanitizeTitle("a---41---b")).isEqualTo("a%41b");
    }

    @Test
    void capitalSigmaBecomesFinalSigmaOnlyAfterACasedLetterAndBeforeNoCasedLetter() {
        String sigma = cp(0x3A3);
        assertThat(Slugs.sanitizeTitle("a-" + sigma)).isEqualTo("a-%cf%83");
        assertThat(Slugs.sanitizeTitle("a9" + sigma)).isEqualTo("a9%cf%83");
        assertThat(Slugs.sanitizeTitle("a_" + sigma)).isEqualTo("a_%cf%83");
        assertThat(Slugs.sanitizeTitle("a." + sigma)).isEqualTo("a-%cf%82");
        assertThat(Slugs.sanitizeTitle(cp(0x391) + sigma)).isEqualTo("%ce%b1%cf%82");
        assertThat(Slugs.sanitizeTitle(cp(0x39F, 0x394, 0x39F, 0x3A3))).isEqualTo("%ce%bf%ce%b4%ce%bf%cf%82");
        assertThat(Slugs.sanitizeTitle("a" + sigma + "a")).isEqualTo("a%cf%83a");
        assertThat(Slugs.sanitizeTitle(sigma)).isEqualTo("%cf%83");
        assertThat(Slugs.sanitizeTitle(cp(0x391) + sigma + "'" + cp(0x391))).isEqualTo("%ce%b1%cf%83%ce%b1");
        assertThat(Slugs.sanitizeTitle(cp(0x391, 0x3A3) + " " + cp(0x391, 0x3A3, 0x391)))
                .isEqualTo("%ce%b1%cf%82-%ce%b1%cf%83%ce%b1");
        assertThat(Slugs.sanitizeTitleForQuery("a-" + sigma)).isEqualTo("a-%cf%83");
    }

    @Test
    void saveOnlyTablesAreAppliedEntryByEntry() {
        int[] hyphens = {
            0xA0, 0x2011, 0x2013, 0x2014, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008,
            0x2009, 0x200A, 0x2028, 0x2029, 0x202F
        };
        for (int c : hyphens) {
            assertThat(Slugs.sanitizeTitle("a" + cp(c) + "b")).as("U+%04X", c).isEqualTo("a-b");
        }
        int[] dropped = {
            0xAD, 0xA1, 0xBF, 0xAB, 0xBB, 0x2039, 0x203A, 0x2018, 0x2019, 0x201C, 0x201D, 0x201A, 0x201B, 0x201E,
            0x201F, 0x2022, 0xA9, 0xAE, 0xB0, 0x2026, 0x2122, 0xB4, 0x2CA, 0x301, 0x341, 0x300, 0x304, 0x30C, 0x200B,
            0x200C, 0x200D, 0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0xFEFF, 0xFFFC
        };
        for (int c : dropped) {
            assertThat(Slugs.sanitizeTitle("a" + cp(c) + "b")).as("U+%04X", c).isEqualTo("ab");
        }
        for (String entity : new String[] {"&nbsp;", "&#8209;", "&#160;", "&ndash;", "&#8211;", "&mdash;", "&#8212;"}) {
            assertThat(Slugs.sanitizeTitle("a" + entity + "b")).as(entity).isEqualTo("a-b");
        }
    }

    @Test
    void textWithALoneSurrogateIsNeitherLowerCasedNorEncoded() {
        assertThat(Slugs.sanitizeTitleForQuery("Ab" + (char) 0xD800 + "C" + cp(0xE9)))
                .isEqualTo("abc");
        assertThat(Slugs.sanitizeTitle("Ab" + (char) 0xD800 + "C" + cp(0xE9))).isEqualTo("abce");
    }

    @Test
    void sanitizingIsNotIdempotentBecauseOctetsCanJoin() {
        assertThat(Slugs.sanitizeTitle("%c2!%a0")).isEqualTo("%c2%a0");
        assertThat(Slugs.sanitizeTitle("%c2%a0")).isEmpty();
    }

    @Test
    void nullInputsAreRejected() {
        assertThatThrownBy(() -> Slugs.sanitizeTitle(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("title");
        assertThatThrownBy(() -> Slugs.sanitizeTitleForQuery(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("title");
        assertThatThrownBy(() -> new SlugContext(null, Optional.empty(), "post", "publish", Optional.empty(), "x"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("site");
        assertThatThrownBy(() -> new SlugContext(SiteId.of(1), Optional.empty(), "post", "publish", null, "x"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("parent");
        assertThatThrownBy(() ->
                        new SlugContext(SiteId.of(1), Optional.empty(), "post", "publish", Optional.empty(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("original");
    }

    @Test
    void slugsStopAtTwoHundredBytesWithoutSplittingACharacter() {
        assertThat(Slugs.sanitizeTitle("a".repeat(300))).isEqualTo("a".repeat(200));
        assertThat(Slugs.sanitizeTitle(cp(0x65E5).repeat(80))).isEqualTo("%e6%97%a5".repeat(22));
        assertThat(Slugs.sanitizeTitle("a".repeat(199) + "-b")).isEqualTo("a".repeat(199));
    }

    @Test
    void theFallbackReplacesAnEmptyResult() {
        assertThat(Slugs.sanitizeTitle("!!!", "124")).isEqualTo("124");
        assertThat(Slugs.sanitizeTitle("", "7")).isEqualTo("7");
        assertThat(Slugs.sanitizeTitle("0", "7")).isEqualTo("0");
    }

    @Test
    void theQueryContextKeepsAccentsAndSaveOnlyCharacters() {
        assertThat(Slugs.sanitizeTitleForQuery("Caf" + cp(0xE9))).isEqualTo("caf%c3%a9");
        assertThat(Slugs.sanitizeTitleForQuery("a" + cp(0x2014) + "b")).isEqualTo("a%e2%80%94b");
    }

    @Test
    void truncationKeepsWholeCharactersAndDropsTrailingHyphens() {
        assertThat(Slugs.truncate("a".repeat(200), 198)).isEqualTo("a".repeat(198));
        assertThat(Slugs.truncate("%e6%97%a5".repeat(22), 197)).isEqualTo("%e6%97%a5".repeat(21));
        assertThat(Slugs.truncate("ab-".repeat(66) + "ab", 198)).isEqualTo("ab-".repeat(65) + "ab");
        assertThat(Slugs.truncate("hello-", 200)).isEqualTo("hello");
        assertThat(Slugs.truncate("a".repeat(200), 191) + "__trashed").hasSize(200);
        assertThat(Slugs.truncate("%e6%97%a5".repeat(22), 191)).isEqualTo("%e6%97%a5".repeat(21));
    }

    @Test
    void phpUrlencodeEncodesLikePhp() {
        assertThat(Slugs.phpUrlencode("caf" + cp(0xE9))).isEqualTo("caf%C3%A9");
        assertThat(Slugs.phpUrlencode("a b-c_d.e~f!")).isEqualTo("a+b-c_d.e%7Ef%21");
    }

    @Test
    void slugsHoldOnlySlugCharacters() {
        String alphabet = "aZ09 -_.%/&;<>!" + cp(0xE9, 0x418, 0x65E5, 0x2014, 0xA0);
        Properties.check(
                "a slug holds only %, a-z, 0-9, _ and -, and at most 200 bytes",
                2_000, Gens.strings(alphabet, 0, 80), title -> {
                    String slug = Slugs.sanitizeTitle(title);
                    assertThat(slug.chars())
                            .allMatch(c -> c == '%'
                                    || c == '_'
                                    || c == '-'
                                    || (c >= 'a' && c <= 'z')
                                    || (c >= '0' && c <= '9'));
                    assertThat(Slugs.byteLength(slug)).isLessThanOrEqualTo(200);
                    assertThat(slug).doesNotStartWith("-").doesNotEndWith("-").doesNotContain("--");
                });
    }
}
