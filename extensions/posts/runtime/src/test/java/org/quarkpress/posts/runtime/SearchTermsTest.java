package org.quarkpress.posts.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SearchTermsTest {

    @Test
    void wordsAndPhrasesBecomeTerms() {
        assertThat(SearchTerms.parse("hello world", false).terms()).containsExactly("hello", "world");
        assertThat(SearchTerms.parse("hello world", false).rawCount()).isEqualTo(2);
        assertThat(SearchTerms.parse("\"hello world\" foo", false).terms()).containsExactly("hello world", "foo");
        assertThat(SearchTerms.parse("foo,bar+baz", false).terms()).containsExactly("foo", "bar", "baz");
        assertThat(SearchTerms.parse("-draft post", false).terms()).containsExactly("-draft", "post");
    }

    @Test
    void stopwordsSingleLettersAndZeroAreDropped() {
        SearchTerms.Parsed parsed = SearchTerms.parse("the cat", false);
        assertThat(parsed.terms()).containsExactly("cat");
        assertThat(parsed.rawCount()).isEqualTo(2);
        assertThat(SearchTerms.parse("The Cat", false).terms()).containsExactly("Cat");
        assertThat(SearchTerms.parse("a cat 0 -", false).terms()).containsExactly("cat");
        assertThat(SearchTerms.parse("x 1", false).terms()).containsExactly("1");
    }

    @Test
    void theWholeStringIsUsedWhenNothingOrTooMuchRemains() {
        assertThat(SearchTerms.parse("a", false).terms()).containsExactly("a");
        assertThat(SearchTerms.parse("one two three four five six seven eight nine ten eleven", false)
                        .terms())
                .containsExactly("one two three four five six seven eight nine ten eleven");
        assertThat(SearchTerms.parse("one two three four five six seven eight nine", false)
                        .terms())
                .hasSize(9);
    }

    @Test
    void lineBreaksAreRemovedAndASentenceIsOneTerm() {
        assertThat(SearchTerms.parse("red\r\ncar", false).cleaned()).isEqualTo("redcar");
        assertThat(SearchTerms.parse("red car", true).terms()).containsExactly("red car");
    }

    @Test
    void aQuotedExclusionIsStillAnExclusion() {
        SearchTerms.Parsed parsed = SearchTerms.parse("\"-draft\" hello", false);
        assertThat(parsed.excluded()).containsExactly("draft");
        assertThat(parsed.positive()).containsExactly("hello");
    }

    @Test
    void theSentenceRankIsSkippedWhenAWordStartsWithAHyphen() {
        assertThat(SearchTerms.parse("red -blue", false).sentenceRankSkipped()).isTrue();
        assertThat(SearchTerms.parse("-blue", false).sentenceRankSkipped()).isTrue();
        assertThat(SearchTerms.parse("red-blue car", false).sentenceRankSkipped())
                .isFalse();
    }

    @Test
    void whatTheRelevanceOrderNeedsIsExposed() {
        SearchTerms.Parsed parsed = SearchTerms.parse("a1 b2 c3 d4 e5 f6 g7 -h8", false);
        assertThat(parsed.rawCount()).isEqualTo(8);
        assertThat(parsed.positive()).hasSize(7);
        assertThat(parsed.sentenceRankSkipped()).isTrue();
        assertThat(SearchTerms.MAX_BYTES).isEqualTo(1600);
    }

    @Test
    void aMissingSearchIsRefused() {
        assertThatThrownBy(() -> SearchTerms.parse(null, false))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("search");
    }
}
