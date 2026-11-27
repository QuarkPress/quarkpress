package org.quarkword.lib.wpformatting;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HtmlSplitTest {

    @Test
    void joinsBackToTheInput() { // A-G126
        String input = "a<b>c<!-- d<e> -->f<![CDATA[ g ]]>h<i";
        List<String> split = Formatting.htmlSplit(input);
        assertThat(split).containsExactly("a", "<b>", "c", "<!-- d<e> -->", "f", "<![CDATA[ g ]]>", "h", "<i", "");
        assertThat(String.join("", split)).isEqualTo(input);
    }

    @Test
    void commentShapes() { // FA36: the leading <!-- counts toward -->
        assertThat(Formatting.htmlSplit("<!-->x")).containsExactly("", "<!-->", "x");
        assertThat(Formatting.htmlSplit("x<!-- never closed <b>")).containsExactly("x", "<!-- never closed <b>", "");
    }

    @Test
    void lessThanSpaceStartsAChunk() {
        assertThat(Formatting.htmlSplit("a < b")).containsExactly("a ", "< b", "");
    }

    @Test
    void unterminatedAtEnd() { // Review Focus 3
        assertThat(Formatting.htmlSplit("<")).containsExactly("", "<", "");
        assertThat(Formatting.htmlSplit("<!--")).containsExactly("", "<!--", "");
        assertThat(Formatting.htmlSplit("<![CDATA[")).containsExactly("", "<![CDATA[", "");
        assertThat(Formatting.htmlSplit("a<")).containsExactly("a", "<", "");
        assertThat(Formatting.htmlSplit("<![CDATA[x]]")).containsExactly("", "<![CDATA[x]]", "");
    }

    @Test
    void chunksCoverTheInputWithoutCopying() {
        String input = "a<b>c<!-- d -->";
        List<String> seen = new ArrayList<>();
        HtmlSplitter.forEachChunk(
                input, (start, end, markup) -> seen.add((markup ? "M:" : "T:") + input.substring(start, end)));
        assertThat(seen).containsExactly("T:a", "M:<b>", "T:c", "M:<!-- d -->", "T:");
    }

    @Test
    void replaceOnlyTouchesMarkup() { // A-G128..G130
        assertThat(Formatting.replaceInHtmlTags("<a\ntitle=\"1\">\n</a>", Map.of("\n", " <!-- wpnl --> ")))
                .isEqualTo("<a <!-- wpnl --> title=\"1\">\n</a>");
        assertThat(Formatting.replaceInHtmlTags("<a title=\"xy\">xy</a>", Map.of("x", "1", "xy", "2", "y", "3")))
                .isEqualTo("<a title=\"2\">xy</a>");
        assertThat(Formatting.replaceInHtmlTags("<!-- x -->x", Map.of("x", "X")))
                .isEqualTo("<!-- X -->x");
    }

    @Test
    void replaceReturnsTheInputWhenNothingChanged() {
        String input = "<a>x</a>";
        assertThat(Formatting.replaceInHtmlTags(input, Map.of("x", "y"))).isSameAs(input);
        assertThat(Formatting.replaceInHtmlTags(input, Map.of("", "y"))).isSameAs(input);
        assertThat(Formatting.replaceInHtmlTags(input, Map.of())).isSameAs(input);
    }

    @Test
    void strtrPicksTheLongestNeedleAndNeverRescans() {
        assertThat(Strtr.apply("aab", Map.of("a", "ab", "ab", "x"))).isEqualTo("abx");
        assertThat(Strtr.apply("xyz", Map.of("", "Q", "y", "Y"))).isEqualTo("xYz");
        assertThat(Strtr.apply("abc", Map.of("abcd", "Q", "b", "B"))).isEqualTo("aBc");
    }

    @Test
    void forwardFindsTheNextNeedleForAnyStart() {
        Forward f = new Forward("a-->b-->", "-->");
        assertThat(f.from(0)).isEqualTo(1);
        assertThat(f.from(1)).isEqualTo(1);
        assertThat(f.from(2)).isEqualTo(5);
        assertThat(f.from(6)).isEqualTo(-1);
        assertThat(f.from(7)).isEqualTo(-1);
        assertThat(f.from(0)).isEqualTo(1);
        assertThat(f.from(-3)).isEqualTo(1);
        assertThat(f.from(100)).isEqualTo(-1);
    }
}
