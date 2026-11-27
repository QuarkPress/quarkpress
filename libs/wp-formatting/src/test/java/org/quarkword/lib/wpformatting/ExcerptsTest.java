package org.quarkword.lib.wpformatting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * wp_trim_words, wp_html_excerpt, excerpt_remove_footnotes and the wp_trim_excerpt helper on the input classes no
 * single reader item covers. Each expected output of a case is WordPress's answer, recorded in the shared case file
 * by a parity run; these tests look the case up by id so both tiers check the same value.
 */
class ExcerptsTest {

    private static final List<RecordedCase> CASES = RecordedCases.load();
    private static final String FOOTNOTE = "<sup data-fn=\"f\" class=\"fn\"><a href=\"#f\" id=\"f-link\">1</a></sup>";

    /** Checks that the direct call and the case's dispatch both give the recorded output of case {@code id}. */
    private static void assertCase(String id, String input, Supplier<String> call) {
        RecordedCase c = CASES.stream()
                .filter(x -> x.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no case " + id));
        assertThat(c.in()).as("input of %s", id).isEqualTo(input);
        assertThat(c.out()).as("recorded output of %s", id).isNotNull();
        String ours = call.get();
        assertThat(ours).as(id).isEqualTo(c.out().asText());
        assertThat(Functions.ours(c.fn(), c.in(), c.args()).asText())
                .as("dispatch of %s", id)
                .isEqualTo(ours);
    }

    @Test
    void manualExcerptWins() { // FT42 step 2
        ExcerptOptions defaults = ExcerptOptions.defaults();
        assertThat(Excerpts.trimExcerpt("  given ", "x y", defaults)).isEqualTo("  given ");
        assertThat(Excerpts.trimExcerpt(" \t", "a ]]> b", defaults)).isEqualTo("a ]]&gt; b");
        // a form feed is not PHP-trimmed, so it is a manual excerpt
        assertThat(Excerpts.trimExcerpt("\f", "x", defaults)).isEqualTo("\f");
        assertThat(Excerpts.trimExcerpt("\0\u000b\r\n", "x", defaults)).isEqualTo("x");
    }

    @Test
    void generatedExcerptUsesTheOptions() { // FT42 steps 7-9
        String content = "one two three four";
        assertThat(Excerpts.trimExcerpt("", content, ExcerptOptions.defaults())).isEqualTo(content);
        assertThat(Excerpts.trimExcerpt(
                        "", content, ExcerptOptions.builder().length(2).build()))
                .isEqualTo("one two [&hellip;]");
        assertThat(Excerpts.trimExcerpt(
                        "",
                        content,
                        ExcerptOptions.builder().length(2).more("!").build()))
                .isEqualTo("one two!");
        assertThat(Excerpts.trimExcerpt(
                        "",
                        content,
                        ExcerptOptions.builder().length(2).more(null).build()))
                .isEqualTo("one two [&hellip;]");
        assertThat(Excerpts.trimExcerpt(
                        "", content, ExcerptOptions.builder().length(0).build()))
                .isEqualTo(" [&hellip;]");
        assertThat(Excerpts.trimExcerpt(
                        "",
                        "abc def",
                        ExcerptOptions.builder()
                                .length(2)
                                .wordCountType(WordCountType.CHARACTERS_EXCLUDING_SPACES)
                                .build()))
                .isEqualTo("ab [&hellip;]");
        assertThat(Excerpts.trimExcerpt(
                        "",
                        "abc def",
                        ExcerptOptions.builder()
                                .length(2)
                                .wordCountType(WordCountType.CHARACTERS_EXCLUDING_SPACES)
                                .utf8Charset(false)
                                .build()))
                .isEqualTo("abc def");
        assertThat(Excerpts.trimExcerpt("", "a " + FOOTNOTE + "b", ExcerptOptions.defaults()))
                .isEqualTo("a b");
    }

    @Test
    void hooksRunInWordPressOrder() { // FT42 step 3
        List<String> calls = new ArrayList<>();
        ExcerptOptions options = ExcerptOptions.builder()
                .shortcodeStripper(t -> {
                    calls.add("stripper:" + t.contains(FOOTNOTE));
                    return t + " s";
                })
                .blockRemover(t -> {
                    calls.add("remover:" + t.contains(FOOTNOTE));
                    return t + " r";
                })
                .contentFilter(t -> {
                    calls.add("content:" + t.contains(FOOTNOTE));
                    return t + " c ]]>";
                })
                .build();
        assertThat(Excerpts.trimExcerpt("", "a" + FOOTNOTE, options)).isEqualTo("a s r c ]]&gt;");
        // the footnote is still there for the stripper and the remover, and gone before the content filter
        assertThat(calls).containsExactly("stripper:true", "remover:true", "content:false");
    }

    @Test
    void onlyAsciiSeparatorsSplitWords() { // Review Focus 1
        assertCase("rf1-tw-1", "a\u000bb c", () -> Excerpts.trimWords("a\u000bb c", 1));
        assertCase("rf1-tw-2", "a\u00a0b c", () -> Excerpts.trimWords("a\u00a0b c", 1));
        assertCase("rf1-tw-3", "a\fb\u2028c d", () -> Excerpts.trimWords("a\fb\u2028c d", 2));
        assertCase("rf1-he-1", "ab &amp\u000bc", () -> Excerpts.htmlExcerpt("ab &amp\u000bc", 7, "!"));
        assertCase("t6-tw-vt-word", "a \u000b b", () -> Excerpts.trimWords("a \u000b b", 5));
        assertCase("t6-tw-cr-lf", "a\r\nb\rc\nd", () -> Excerpts.trimWords("a\r\nb\rc\nd", 3));
        assertCase("t6-he-nbsp", "ab &a\u00a0c d", () -> Excerpts.htmlExcerpt("ab &a\u00a0c d", 6, "!"));
        assertCase("t6-he-ff", "ab &am\fc", () -> Excerpts.htmlExcerpt("ab &am\fc", 7, "!"));
        assertCase("t6-he-ff-kept", "ab\f cd", () -> Excerpts.htmlExcerpt("ab\f cd", 3, "!"));
        assertCase(
                "t6-ch-collapse",
                "  a \n\t b  \u00a0",
                () -> Excerpts.trimWords(
                        "  a \n\t b  \u00a0", 5, null, WordCountType.CHARACTERS_INCLUDING_SPACES, true));
    }

    @Test
    void cutsNeverSplitASurrogatePair() { // Review Focus 2
        assertCase("rf2-he-1", "a😀b", () -> Excerpts.htmlExcerpt("a😀b", 2, "…"));
        assertCase("rf2-he-2", "😀".repeat(3), () -> Excerpts.htmlExcerpt("😀".repeat(3), 1));
        assertCase(
                "rf2-tw-1",
                "😀".repeat(4),
                () -> Excerpts.trimWords("😀".repeat(4), 2, null, WordCountType.CHARACTERS_INCLUDING_SPACES, true));
        // the partial-entity rule counts UTF-8 bytes: an emoji is 4 of the 6
        assertCase("t6-he-emoji-6", "ab &😀ab x", () -> Excerpts.htmlExcerpt("ab &😀ab x", 7));
        assertCase("t6-he-emoji-7", "ab &😀abc x", () -> Excerpts.htmlExcerpt("ab &😀abc x", 8));
        assertCase("t6-he-bytes-6", "ab &ééé x", () -> Excerpts.htmlExcerpt("ab &ééé x", 7));
        assertCase("t6-he-bytes-8", "ab &éééé x", () -> Excerpts.htmlExcerpt("ab &éééé x", 8));
        // a lone surrogate is one U+FFFD after tag stripping, never half a pair
        assertThat(Excerpts.htmlExcerpt("a\ud83db", 2)).isEqualTo("a�");
        assertThat(Excerpts.trimWords("\ude00\ude00", 1, null, WordCountType.CHARACTERS_INCLUDING_SPACES, true))
                .isEqualTo("�&hellip;");
    }

    @Test
    void unterminatedFootnoteMarker() { // Review Focus 3
        String open = "a<sup data-fn=\"x\" class=\"y\"><a href=\"#x\" id=\"x-link\">1</a>";
        assertCase("rf3-fn-1", open, () -> Excerpts.removeFootnotes(open));
        assertCase("rf3-fn-2", "a<sup data-fn=\"x\"", () -> Excerpts.removeFootnotes("a<sup data-fn=\"x\""));
        String nested = "<sup data-fn=\"<sup data-fn=\"x\" class=\"y\"><a href=\"#x\" id=\"x-link\">1</a></sup>";
        assertCase("t6-fn-nested-start", nested, () -> Excerpts.removeFootnotes(nested));
    }

    @Test
    void limitsAtTheEdges() { // Review Focus 5
        int[] limits = {0, -2, Integer.MIN_VALUE, Integer.MAX_VALUE, 3};
        for (int k = 0; k < limits.length; k++) {
            int n = limits[k];
            assertCase("rf5-tw-" + (k + 1), "one two three", () -> Excerpts.trimWords("one two three", n));
        }
        assertCase(
                "rf5-tw-6",
                "one two three",
                () -> Excerpts.trimWords("one two three", -1, null, WordCountType.CHARACTERS_INCLUDING_SPACES, true));
        int[] counts = {-1, Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (int k = 0; k < counts.length; k++) {
            int n = counts[k];
            assertCase("rf5-he-" + (k + 1), "hello world", () -> Excerpts.htmlExcerpt("hello world", n, "!"));
        }
        // a split limit of 0 or -1 is unlimited, one below -1 keeps the whole text as one piece
        assertCase("t6-tw-limit-minus3", "one two three", () -> Excerpts.trimWords("one two three", -3));
        assertCase("t6-tw-empty-minus1", "", () -> Excerpts.trimWords("", -1));
        assertCase("t6-tw-empty-zero", "", () -> Excerpts.trimWords("", 0));
        // character mode keeps num + 1 code points, a negative length dropping that many from the end
        WordCountType chars = WordCountType.CHARACTERS_INCLUDING_SPACES;
        assertCase("t6-ch-minus3", "abcdef", () -> Excerpts.trimWords("abcdef", -3, null, chars, true));
        assertCase("t6-ch-minus7", "abcdef", () -> Excerpts.trimWords("abcdef", -7, null, chars, true));
        assertCase("t6-ch-min", "abcdef", () -> Excerpts.trimWords("abcdef", Integer.MIN_VALUE, null, chars, true));
        assertCase("t6-ch-max", "abcdef", () -> Excerpts.trimWords("abcdef", Integer.MAX_VALUE, null, chars, true));
        assertCase("t6-he-minus-all", "hello world", () -> Excerpts.htmlExcerpt("hello world", -11, "!"));
    }

    @Test
    void wordCountTypeFromWordPress() { // FT40 step 4, R7
        assertThat(WordCountType.fromWordPress("words")).isEqualTo(WordCountType.WORDS);
        assertThat(WordCountType.fromWordPress("")).isEqualTo(WordCountType.WORDS);
        assertThat(WordCountType.fromWordPress("Characters_excluding_spaces")).isEqualTo(WordCountType.WORDS);
        assertThat(WordCountType.fromWordPress("characters_excluding_spaces"))
                .isEqualTo(WordCountType.CHARACTERS_EXCLUDING_SPACES);
        assertThat(WordCountType.fromWordPress("characters_including_spaces"))
                .isEqualTo(WordCountType.CHARACTERS_INCLUDING_SPACES);
        assertThat(WordCountType.fromWordPress("characters")).isEqualTo(WordCountType.CHARACTERS_INCLUDING_SPACES);
        assertThat(WordCountType.fromWordPress("charactersfoo")).isEqualTo(WordCountType.CHARACTERS_INCLUDING_SPACES);
        assertThatNullPointerException().isThrownBy(() -> WordCountType.fromWordPress(null));
    }

    @Test
    void defaultsAndNulls() { // R1
        assertThat(Excerpts.trimWords("one two", 1, null)).isEqualTo("one&hellip;");
        assertThat(Excerpts.trimWords("one two", 1, "")).isEqualTo("one");
        assertThat(Excerpts.htmlExcerpt("abc", 1, null)).isEqualTo("a");
        assertThat(Excerpts.trimWords("1 ".repeat(60), Excerpts.DEFAULT_WORDS))
                .isEqualTo("1 ".repeat(54) + "1" + Excerpts.DEFAULT_MORE);
        String same = "no footnotes here";
        assertThat(Excerpts.removeFootnotes(same)).isSameAs(same);
        assertThatNullPointerException().isThrownBy(() -> Excerpts.trimWords(null, 1));
        assertThatNullPointerException().isThrownBy(() -> Excerpts.trimWords("a", 1, null, null, true));
        assertThatNullPointerException().isThrownBy(() -> Excerpts.htmlExcerpt(null, 1));
        assertThatNullPointerException().isThrownBy(() -> Excerpts.removeFootnotes(null));
        assertThatNullPointerException().isThrownBy(() -> Excerpts.trimExcerpt(null, "", ExcerptOptions.defaults()));
        assertThatNullPointerException().isThrownBy(() -> Excerpts.trimExcerpt("", null, ExcerptOptions.defaults()));
        assertThatNullPointerException().isThrownBy(() -> Excerpts.trimExcerpt("", "", null));
        assertThatNullPointerException()
                .isThrownBy(() -> ExcerptOptions.builder().shortcodeStripper(null));
        assertThatNullPointerException()
                .isThrownBy(() -> ExcerptOptions.builder().blockRemover(null));
        assertThatNullPointerException()
                .isThrownBy(() -> ExcerptOptions.builder().contentFilter(null));
        assertThatNullPointerException()
                .isThrownBy(() -> ExcerptOptions.builder().wordCountType(null));
        // a hook that returns null is a caller error
        assertThatNullPointerException()
                .isThrownBy(() -> Excerpts.trimExcerpt(
                        "",
                        "x",
                        ExcerptOptions.builder().contentFilter(t -> null).build()));
    }
}
