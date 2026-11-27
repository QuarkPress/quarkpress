package org.quarkword.lib.wpformatting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * convert_smilies on the input classes no single reader item covers, plus the table and the options. Each expected
 * output of a case is WordPress's answer, recorded in the shared case file by a parity run; these tests look the
 * case up by id so both tiers check the same value.
 */
class SmiliesTest {

    private static final List<RecordedCase> CASES = RecordedCases.load();
    private static final String BASE = "https://example.com/wp-includes/images/smilies/";

    private static void assertCase(String id, String input) {
        RecordedCase c = CASES.stream()
                .filter(x -> x.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no case " + id));
        assertThat(c.fn()).as("fn of %s", id).isEqualTo("smilies");
        assertThat(c.in()).as("input of %s", id).isEqualTo(input);
        assertThat(c.out()).as("recorded output of %s", id).isNotNull();
        assertThat(Functions.smilies(c.args()).apply(input))
                .as(id)
                .isEqualTo(c.out().asText());
    }

    @Test
    void defaultTableHas43Codes() { // FT31
        List<String> codes = Smilies.codes();
        assertThat(codes).hasSize(43).doesNotContain("8)").doesNotHaveDuplicates();
        assertThat(codes).contains(":mrgreen:", ":-|", ":|", ";)", "8O", ":!:", ":?:", ":???:");
        assertThatThrownBy(() -> codes.add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void codesAreLongestFirstWithinEachFirstCharacter() { // FT32
        List<String> codes = Smilies.codes();
        var finished = new HashSet<Character>();
        for (int i = 0; i < codes.size(); i++) {
            char first = codes.get(i).charAt(0);
            if (i > 0 && codes.get(i - 1).charAt(0) == first) {
                assertThat(codes.get(i).length())
                        .as("%s after %s", codes.get(i), codes.get(i - 1))
                        .isLessThanOrEqualTo(codes.get(i - 1).length());
            } else {
                assertThat(finished.add(first))
                        .as("group of %s is contiguous", first)
                        .isTrue();
            }
        }
    }

    // Review Focus 1: only a line feed ends a line; CR and NBSP are spaces, VT and NEL are neither
    @Test
    void onlyLineFeedEndsALine() {
        assertCase("rf1-sm-1", "a\r:)");
        assertCase("rf1-sm-2", "a :)");
        assertCase("rf1-sm-3", "a\u000b:) b");
        assertCase("rf1-sm-4", ":)\u0085");
        assertCase("t5-crlf", ":)\r\n:-D\r\n");
        assertCase("t5-ff-nul", "a\f:) \u0000:) :)\u0000");
    }

    // Review Focus 3: a "<" with no ">" after it stays in its text chunk
    @Test
    void unterminatedAtEnd() {
        assertCase("rf3-sm-1", ":) <");
        assertCase("rf3-sm-2", "<code :)");
    }

    @Test
    void multibyteNeighboursAreNotSpaces() { // hazard 1: no byte of a multibyte char counts as a space
        assertCase("t5-high-a0", "à:) :)à 😠:)");
        assertCase("t5-nbsp-entity-case", "a &NBSP;:) &nbsp:) b");
    }

    @Test
    void skippedElementsArePrefixMatchedAndClosedExactly() { // FT33, B-G139, B-G140
        assertCase(
                "t5-skip-prefixes",
                "<codex>:)</code> :) <scripts>:)</script> :) <styled>:)</style> :) <textareas>:)</textarea> :)");
        assertCase("t5-close-needs-same-name", "<pre>:)</code> :) </pre> :)");
        assertCase("t5-tag-spans-lines", "<a\n:)\n> :)");
    }

    @Test
    void imageUsesTheBaseVerbatim() { // FT34, DC4
        String img = Smilies.wordPress("//cdn.test/s/?a=1&amp;b=").apply(":mrgreen:");
        assertThat(img)
                .isEqualTo("<img src=\"//cdn.test/s/?a=1&amp;b=mrgreen.png\" alt=\":mrgreen:\" class=\"wp-smiley\""
                        + " style=\"height: 1em; max-height: 1em;\" />");
    }

    @Test
    void disabledReturnsItsInput() { // FT30, B-G148
        Smilies off = Smilies.builder().enabled(false).build();
        String text = ":) :mrgreen:";
        assertThat(off.apply(text)).isSameAs(text);
        String plain = "no smiley here";
        assertThat(Smilies.wordPress(BASE).apply(plain)).isEqualTo(plain);
    }

    @Test
    void nullsAndAMissingBaseAreRejected() {
        assertThatNullPointerException().isThrownBy(() -> Smilies.wordPress(null));
        assertThatNullPointerException().isThrownBy(() -> Smilies.builder().imageBaseUrl(null));
        assertThatNullPointerException()
                .isThrownBy(() -> Smilies.wordPress(BASE).apply(null));
        assertThatIllegalStateException().isThrownBy(() -> Smilies.builder().build());
    }
}
