package org.quarkpress.taxonomy.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.SiteId;
import org.quarkpress.posts.SlugContext;
import org.quarkpress.posts.SlugSanitizer;
import org.quarkpress.posts.runtime.DefaultSlugSanitizer;
import org.quarkpress.testing.gen.Gens;
import org.quarkpress.testing.gen.Properties;

class TermTextTest {

    private static String cp(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    private final TermSlugs slugs = new TermSlugs(new DefaultSlugSanitizer());

    private String slugOf(String text) {
        return slugs.sanitize(SiteId.of(1), "post_tag", text);
    }

    @Test
    void namesAreStoredAsWordPressStoresThem() {
        String[][] table = {
            {"  Rock & Roll  ", "Rock &amp; Roll"},
            {"a < b", "a &lt; b"},
            {"<b>Bold</b> name", "Bold name"},
            {"<em>Hi</em> & bye", "Hi &amp; bye"},
            {"Tom's \"best\"", "Tom's \"best\""},
            {"x &amp; y", "x &amp; y"},
            {"news\tline\nbreak", "news line break"},
            {"%41%42 octets", "octets"},
            {"a\\b", "a\\b"},
            {"<script>alert(1)</script>", ""},
            {"Caf" + cp(0xE9), "Caf" + cp(0xE9)},
            {"0", "0"},
            {"%%4141x", "x"},
            {"a <\nb", "a &lt; b"},
            {"x <y", "x &lt;y"},
            {"a" + (char) 1 + "b", "ab"},
            {"a " + (char) 0, "a "},
            {(char) 0 + " a", " a"},
            {"x %41 " + (char) 0, "x "},
            {"\u0000\r'\n\r<b>", " '"},
            {"a\\'<b>", "a\\'"},
            {"\"x\" <i>", "\"x\""},
            {"\\\\<b>", "\\\\"},
            {"it's \"q\" <b>x</b>", "it's \"q\" x"}
        };
        for (String[] row : table) {
            assertThat(TermText.name(row[0])).as(row[0]).isEqualTo(row[1]);
        }
    }

    @Test
    void sanitizeTextFieldAloneKeepsAmpersandsAndDropsLoneSurrogates() {
        assertThat(TermText.sanitizeTextField("  Rock & Roll ")).isEqualTo("Rock & Roll");
        assertThat(TermText.sanitizeTextField("a" + (char) 0xD800 + "b")).isEmpty();
        assertThat(TermText.sanitizeTextField("x  %4a  y")).isEqualTo("x y");
    }

    @Test
    void sanitizeTextFieldEscapesALessThanBeforeALineFeed() {
        assertThat(TermText.sanitizeTextField("a <\n> b")).isEqualTo("a &lt; > b");
    }

    @Test
    void sanitizeTextFieldTrimsNulLikePhp() {
        assertThat(TermText.sanitizeTextField("a \u0000")).isEqualTo("a");
        assertThat(TermText.sanitizeTextField("\u0000 a")).isEqualTo("a");
        assertThat(TermText.sanitizeTextField("\u0000")).isEmpty();
        assertThat(TermText.sanitizeTextField("x %41 \u0000")).isEqualTo("x");
        assertThat(TermText.sanitizeTextField("a" + (char) 0x0B)).isEqualTo("a");
    }

    @Test
    void descriptionsKeepTheBasicTagsAndAreNotTrimmed() {
        assertThat(TermText.description(
                        "<p>Hi <a href=\"http://x\" rel=\"nofollow\" target=\"_blank\" onclick=\"x\">l</a>"
                                + " <strong>s</strong><em>e</em><img src=x></p>"))
                .isEqualTo("Hi <a href=\"http://x\" rel=\"nofollow\" target=\"_blank\">l</a>"
                        + " <strong>s</strong><em>e</em>");
        assertThat(TermText.description(
                        "<script>x</script><b>b</b> <a href=\"javascript:alert(1)\">j</a> <p class='c'>p</p> &copy;"))
                .isEqualTo("x<b>b</b> <a href=\"alert(1)\">j</a> p &copy;");
        assertThat(TermText.description("  spaced  ")).isEqualTo("  spaced  ");
    }

    @Test
    void slugsAreSanitizedTitles() {
        assertThat(slugOf(TermText.name("  Rock & Roll  "))).isEqualTo("rock-roll");
        assertThat(slugOf("News!")).isEqualTo("news");
        assertThat(slugOf(cp(0xC7) + "a va?")).isEqualTo("ca-va");
        assertThat(slugOf(cp(0x65E5, 0x672C, 0x8A9E) + " " + cp(0x30BF, 0x30B0)))
                .isEqualTo("%e6%97%a5%e6%9c%ac%e8%aa%9e-%e3%82%bf%e3%82%b0");
        assertThat(slugOf(cp(0xE9) + "moji " + cp(0x1F600))).isEqualTo("emoji-%f0%9f%98%80");
        assertThat(slugOf("100%")).isEqualTo("100");
        assertThat(slugOf("a/b")).isEqualTo("a-b");
        assertThat(slugOf("My Slug!")).isEqualTo("my-slug");
        assertThat(slugOf("!!!")).isEmpty();
        assertThat(slugOf("Tom's \"best\"")).isEqualTo("toms-best");
    }

    @Test
    void aGivenSlugIsSanitizedSlashedAsInWordPress() {
        String[][] table = {
            {"\u0000<b></b>", "0"},
            {"a\u0000b", "a0b"},
            {"a\u0000", "a0"},
            {"\u0000a", "0a"},
            {"\u0000", "0"},
            {"A \u0000 B", "a-0-b"},
            {"%4\u0000", "40"},
            {"%\u0000a", "0a"},
            {"%\u0000\u0000", "00"},
            {"x%a\u0000", "xa0"},
            {"it's", "its"},
            {"say \"hi\"", "say-hi"},
            {"a\\b", "ab"},
            {"%\\41", "41"},
            {"%'41", "41"},
            {"a\\'b", "ab"},
            {"\\\\", ""}
        };
        for (String[] row : table) {
            assertThat(slugOf(row[0])).as(row[0]).isEqualTo(row[1]);
        }
    }

    @Test
    void aSlugMadeFromANameIsSanitizedUnslashed() {
        assertThat(slugs.generate(SiteId.of(1), "post_tag", "%\\41 x")).isEqualTo("41-x");
        assertThat(slugs.generate(SiteId.of(1), "post_tag", "Rock &amp; Roll")).isEqualTo("rock-roll");
    }

    @Test
    void aTermSlugGetsTheContextOfATerm() {
        List<SlugContext> seen = new ArrayList<>();
        SlugSanitizer recorder = (text, context) -> {
            seen.add(context);
            return text;
        };
        new TermSlugs(recorder).sanitize(SiteId.of(3), "category", "Some Name");
        assertThat(seen)
                .containsExactly(
                        new SlugContext(SiteId.of(3), Optional.empty(), "category", "", Optional.empty(), "Some Name"));
    }

    @Test
    void asciiLowerFoldsOnlyAToZ() {
        assertThat(TermText.asciiLower("NEWS")).isEqualTo("news");
        assertThat(TermText.asciiLower("CAF" + cp(0xC9))).isEqualTo("caf" + cp(0xC9));
        assertThat(TermText.asciiLower(cp(0x130) + "I")).isEqualTo(cp(0x130) + "i");
    }

    @Test
    void blankMeansOnlyWhitespaceThatWordPressTrims() {
        assertThat(TermText.blank("")).isTrue();
        assertThat(TermText.blank(" \t\n\r" + (char) 0x0B)).isTrue();
        assertThat(TermText.blank("0")).isFalse();
        assertThat(TermText.blank("" + (char) 0)).isFalse();
        assertThat(TermText.hasNul("a" + (char) 0)).isTrue();
        assertThat(TermText.hasNul("a")).isFalse();
    }

    @Test
    void sanitizingANameTwiceChangesNothing() {
        Properties.check(
                "a stored name is a fixed point of the name filters",
                500,
                Gens.strings("ab &<>\"'%41;#x\t\n" + cp(0xE9), 0, 24),
                text -> {
                    String once = TermText.name(text);
                    assertThat(TermText.name(once)).as(text).isEqualTo(once);
                });
    }

    @Test
    void ordersOtherThanAscendingAreDescending() {
        for (String order : List.of("ASC", "asc", "aSc")) {
            assertThat(TermOrders.ascending(order)).as(order).isTrue();
        }
        for (String order : List.of("DESC", "desc", "", "up", "ASC ", " asc")) {
            assertThat(TermOrders.ascending(order)).as(order).isFalse();
        }
        assertThat(TermOrders.ascending(null)).isFalse();
    }
}
