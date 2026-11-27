package org.quarkword.lib.wpformatting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * wptexturize on the input classes no single reader item covers, plus the options. Each expected output of a case
 * is WordPress's answer, recorded in the shared case file by a parity run; these tests look the case up by id so
 * both tiers check the same value.
 */
class TexturizeTest {

    private static final List<RecordedCase> CASES = RecordedCases.load();

    private static void assertCase(String id, String input) {
        RecordedCase c = CASES.stream()
                .filter(x -> x.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no case " + id));
        assertThat(c.fn()).as("fn of %s", id).isEqualTo("texturize");
        assertThat(c.in()).as("input of %s", id).isEqualTo(input);
        assertThat(c.out()).as("recorded output of %s", id).isNotNull();
        assertThat(Functions.texturize(c.args()).apply(input))
                .as(id)
                .isEqualTo(c.out().asText());
    }

    // B-G58: primes on their own, with the opening-quote placeholder
    @Test
    void primesDirect() {
        assertThat(Primes.apply("a 5' b", '\'', "&#8242;", QuoteRules.OPENING_SINGLE, "&#8217;"))
                .isEqualTo("a 5&#8242; b");
    }

    // B-G130: switching texturize off is an option of the instance
    @Test
    void disabledReturnsItsInput() {
        String input = "\"a\"";
        assertThat(Texturize.builder().enabled(false).build().apply(input)).isSameAs(input);
        assertThat(Texturize.builder().enabled(true).build().apply(input)).isEqualTo("&#8220;a&#8221;");
    }

    // Review Focus 1: VT, U+2028, FF and a leading NBSP against texturize's space set
    @Test
    void onlySpCountsAsSpace() {
        assertCase("rf1-tx-1", "a\u000b'b'");
        assertCase("rf1-tx-2", "a \"b\"");
        assertCase("rf1-tx-3", "x\f-- y");
        assertCase("rf1-tx-4", " '99");
    }

    // Review Focus 2: a quote after a character outside the BMP is not after an opener
    @Test
    void quoteAfterEmojiCloses() {
        assertCase("rf2-tx-1", "😀\"a\"");
        assertCase("rf2-tx-2", "😀'99 x");
    }

    // Review Focus 3
    @Test
    void unterminatedAtEnd() {
        assertCase("rf3-tx-1", "\"a\" <");
        assertCase("rf3-tx-2", "\"a\" [code ");
        assertCase("rf3-tx-3", "<!-- \"a\"");
    }

    // Review Focus 4: WordPress's placeholder comment typed by an author is a comment; QuarkWord's sentinels
    // contain "<", which no text segment holds
    @Test
    void sentinelsCannotBeTyped() {
        assertCase("rf4-tx-1", "<!--osq-->'a' \"b\"");
        assertCase("rf4-tx-2", "'a'\"b\"");
        assertCase("rf4-tx-3", "&#8216;x' y");
        assertThat(QuoteRules.APOSTROPHE).contains("<");
        assertThat(QuoteRules.OPENING_SINGLE).contains("<");
        assertThat(QuoteRules.OPENING_DOUBLE).contains("<");
        for (String sentinel : List.of(QuoteRules.APOSTROPHE, QuoteRules.OPENING_SINGLE, QuoteRules.OPENING_DOUBLE)) {
            assertThat(sentinel.chars()).noneMatch(c -> c >= 0xE000 && c <= 0xF8FF);
        }
    }

    @Test
    void wordPressDefaults() {
        assertThat(Texturize.DEFAULT_NO_TEXTURIZE_TAGS).containsExactly("pre", "code", "kbd", "style", "script", "tt");
        assertThat(Texturize.DEFAULT_NO_TEXTURIZE_SHORTCODES).containsExactly("code");
        Texturize wordPress = Texturize.wordPress();
        assertThat(wordPress.apply("<code>\"a\"</code>\"b\"")).isEqualTo("<code>\"a\"</code>&#8220;b&#8221;");
        // no shortcode is registered by default, so [code] protects nothing (B-G120)
        assertThat(wordPress.apply("[code]\"a\"[/code]")).isEqualTo("[code]&#8221;a&#8221;[/code]");
        assertThat(Texturize.builder().build().apply("It's")).isEqualTo(wordPress.apply("It's"));
        assertThat(wordPress.apply("")).isEmpty();
        assertThat(wordPress.apply("0")).isEqualTo("0");
    }

    @Test
    void builderListsReplaceTheDefaults() {
        Texturize noTags = Texturize.builder().noTexturizeTags(List.of()).build();
        assertThat(noTags.apply("<pre>\"a\"</pre>")).isEqualTo("<pre>&#8220;a&#8221;</pre>");

        Texturize code = Texturize.builder()
                .registeredShortcodes(List.of("code", "x"))
                .noTexturizeShortcodes(List.of("x"))
                .build();
        assertThat(code.apply("[code]\"a\"[/code]")).isEqualTo("[code]&#8220;a&#8221;[/code]");
        assertThat(code.apply("[x]\"a\"[/x]\"b\"")).isEqualTo("[x]\"a\"[/x]&#8220;b&#8221;");
    }

    @Test
    void builderCopiesItsLists() {
        List<String> tags = new ArrayList<>(List.of("pre"));
        Texturize texturize = Texturize.builder().noTexturizeTags(tags).build();
        tags.add("b");
        assertThat(texturize.apply("<b>\"a\"</b>")).isEqualTo("<b>&#8220;a&#8221;</b>");
    }

    @Test
    void customStringsAreUsed() {
        TexturizeStrings strings =
                new TexturizeStrings("«", "»", "’", "′", "″", "‹", "›", "–", "—", java.util.Map.of());
        Texturize texturize = Texturize.builder().strings(strings).build();
        assertThat(texturize.apply("\"a\" 'b' it's 5' a -- b a - b 'cause"))
                .isEqualTo("«a» ‹b› it’s 5′ a — b a – b ‹cause");
    }

    @Test
    void nullIsRejected() {
        assertThatNullPointerException().isThrownBy(() -> Texturize.wordPress().apply(null));
        assertThatNullPointerException().isThrownBy(() -> Texturize.builder().strings(null));
        assertThatNullPointerException().isThrownBy(() -> Texturize.builder().noTexturizeTags(null));
        assertThatNullPointerException().isThrownBy(() -> Texturize.builder().noTexturizeShortcodes(null));
        assertThatNullPointerException().isThrownBy(() -> Texturize.builder().registeredShortcodes(null));
        List<String> withNull = new ArrayList<>();
        withNull.add(null);
        assertThatNullPointerException().isThrownBy(() -> Texturize.builder().noTexturizeTags(withNull));
    }

    @Test
    void anEmptyClosingQuoteIsRejected() {
        TexturizeStrings english = TexturizeStrings.english();
        assertThatThrownBy(() -> new TexturizeStrings(
                        english.openingDoubleQuote(),
                        "",
                        english.apostrophe(),
                        english.prime(),
                        english.doublePrime(),
                        english.openingSingleQuote(),
                        english.closingSingleQuote(),
                        english.enDash(),
                        english.emDash(),
                        english.cockney()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("closingDoubleQuote must not be empty");
    }
}
