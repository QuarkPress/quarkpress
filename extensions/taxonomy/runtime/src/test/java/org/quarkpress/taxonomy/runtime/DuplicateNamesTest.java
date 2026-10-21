package org.quarkpress.taxonomy.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.TermId;

/** WordPress's duplicate-name decision, on the behaviour reference's examples. */
class DuplicateNamesTest {

    private static final Optional<TermId> TOP = Optional.empty();

    private static String cp(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    private static DuplicateNames.Stored stored(long id, String name, String slug, Optional<TermId> parent) {
        return new DuplicateNames.Stored(TermId.of(id), name, slug, parent);
    }

    @Test
    void aTagNamedLikeAnotherIgnoringAsciiCaseIsADuplicate() {
        DuplicateNames.Stored news = stored(5, "News", "news", TOP);
        assertThat(DuplicateNames.conflict("news", "news", false, false, TOP, List.of(news), Optional.of(news)))
                .contains(TermId.of(5));
    }

    @Test
    void anExplicitUnusedSlugLetsADuplicateNameThrough() {
        DuplicateNames.Stored news = stored(5, "News", "news", TOP);
        assertThat(DuplicateNames.conflict("News", "news-alt", true, false, TOP, List.of(news), Optional.empty()))
                .isEmpty();
    }

    @Test
    void anExplicitSlugThatIsTakenDoesNot() {
        DuplicateNames.Stored news = stored(5, "News", "news", TOP);
        DuplicateNames.Stored other = stored(6, "Other", "other", TOP);
        assertThat(DuplicateNames.conflict("News", "other", true, false, TOP, List.of(news), Optional.of(other)))
                .contains(TermId.of(5));
    }

    @Test
    void accentsAndNonAsciiCaseAreNotFolded() {
        DuplicateNames.Stored cafe = stored(5, "Caf" + cp(0xE9), "cafe", TOP);
        assertThat(DuplicateNames.conflict(
                        "CAF" + cp(0xC9), "cafe", false, false, TOP, List.of(cafe), Optional.of(cafe)))
                .isEmpty();
        DuplicateNames.Stored plain = stored(6, "Cafe", "cafe", TOP);
        assertThat(DuplicateNames.conflict(
                        "Caf" + cp(0xE9), "cafe", false, false, TOP, List.of(plain), Optional.of(plain)))
                .isEmpty();
    }

    @Test
    void aCategorySiblingWithTheSlugIsADuplicate() {
        DuplicateNames.Stored news = stored(5, "News", "news", TOP);
        assertThat(DuplicateNames.conflict("news", "news", false, true, TOP, List.of(news), Optional.of(news)))
                .contains(TermId.of(5));
    }

    @Test
    void aCategoryWithAnotherSlugAndNoExactSiblingIsNot() {
        DuplicateNames.Stored news = stored(5, "News", "headlines", TOP);
        assertThat(DuplicateNames.conflict("news", "news", false, true, TOP, List.of(news), Optional.empty()))
                .isEmpty();
    }

    @Test
    void anExactSiblingNameIsADuplicateWhenNoOtherSlugIsGiven() {
        DuplicateNames.Stored news = stored(5, "News", "headlines", TOP);
        assertThat(DuplicateNames.conflict("News", "news", false, true, TOP, List.of(news), Optional.empty()))
                .contains(TermId.of(5));
        assertThat(DuplicateNames.conflict("News", "fresh", true, true, TOP, List.of(news), Optional.empty()))
                .isEmpty();
    }

    @Test
    void theSlugFallbackNeedsASiblingSlug() {
        Optional<TermId> world = Optional.of(TermId.of(9));
        DuplicateNames.Stored news = stored(5, "News", "headlines", world);
        DuplicateNames.Stored elsewhere = stored(7, "Elsewhere", "news", TOP);
        assertThat(DuplicateNames.conflict("news", "news", false, true, world, List.of(news), Optional.of(elsewhere)))
                .isEmpty();
        DuplicateNames.Stored sibling = stored(8, "Sibling", "news", world);
        assertThat(DuplicateNames.conflict("news", "news", false, true, world, List.of(news), Optional.of(sibling)))
                .contains(TermId.of(8));
    }

    @Test
    void aGivenSlugEqualToTheMatchesSlugStillNeedsAnExactSiblingInACategory() {
        DuplicateNames.Stored news = stored(5, "News", "news", TOP);
        assertThat(DuplicateNames.conflict("News", "news", true, true, TOP, List.of(news), Optional.empty()))
                .contains(TermId.of(5));
        assertThat(DuplicateNames.conflict("news", "news", true, true, TOP, List.of(news), Optional.empty()))
                .isEmpty();
    }

    @Test
    void aGivenSlugTakenUnderAnotherParentIsNoDuplicateEvenWithAnExactSibling() {
        DuplicateNames.Stored news = stored(5, "News", "news", TOP);
        DuplicateNames.Stored taken = stored(7, "Other", "taken", Optional.of(TermId.of(9)));
        assertThat(DuplicateNames.conflict("News", "taken", true, true, TOP, List.of(news), Optional.of(taken)))
                .isEmpty();
    }

    @Test
    void theFirstCandidateInNameOrderIsTheMatch() {
        DuplicateNames.Stored upper = stored(5, "NEWS", "news", TOP);
        DuplicateNames.Stored lower = stored(6, "news", "news-2", TOP);
        assertThat(DuplicateNames.conflict(
                        "News", "news", false, false, TOP, List.of(upper, lower), Optional.of(upper)))
                .contains(TermId.of(5));
    }

    @Test
    void aGivenSlugEqualToTheMatchesSlugIsADuplicateInAFlatTaxonomy() {
        DuplicateNames.Stored news = stored(5, "News", "news", TOP);
        assertThat(DuplicateNames.conflict("News", "news", true, false, TOP, List.of(news), Optional.empty()))
                .contains(TermId.of(5));
    }
}
