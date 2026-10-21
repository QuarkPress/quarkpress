package org.quarkpress.taxonomy.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.TermId;

/** WordPress's slug uniqueness on the behaviour reference's examples, against an in-memory terms table. */
class UniqueTermSlugsTest {

    record Row(long id, String taxonomy, String name, String slug, Long parent) {}

    /** One site's terms; lookups for {@code taxonomy}. */
    static final class Table implements UniqueTermSlugs.Lookup {
        final List<Row> rows = new ArrayList<>();
        final String taxonomy;

        Table(String taxonomy) {
            this.taxonomy = taxonomy;
        }

        Table add(long id, String taxonomy, String name, String slug, Long parent) {
            rows.add(new Row(id, taxonomy, name, slug, parent));
            return this;
        }

        @Override
        public boolean takenInTaxonomy(String slug) {
            return rows.stream()
                    .anyMatch(
                            row -> row.taxonomy().equals(taxonomy) && row.slug().equals(slug));
        }

        @Override
        public Optional<UniqueTermSlugs.Parent> parent(TermId id) {
            return rows.stream()
                    .filter(row -> row.id() == id.value() && row.taxonomy().equals(taxonomy))
                    .findFirst()
                    .map(row -> new UniqueTermSlugs.Parent(
                            row.slug(), Optional.ofNullable(row.parent()).map(TermId::of)));
        }

        @Override
        public boolean existsAnywhere(String slugOrName) {
            return rows.stream()
                    .anyMatch(row -> row.slug().equals(slugOrName)
                            || row.name()
                                    .toLowerCase(java.util.Locale.ROOT)
                                    .equals(slugOrName.toLowerCase(java.util.Locale.ROOT)));
        }

        @Override
        public boolean slugTakenAnywhere(String slug, Optional<TermId> except) {
            return rows.stream()
                    .anyMatch(row -> row.slug().equals(slug)
                            && except.map(id -> id.value() != row.id()).orElse(true));
        }
    }

    @Test
    void aSlugTakenInTheTaxonomyGetsANumber() {
        Table tags = new Table("post_tag").add(2, "post_tag", "News", "news", null);
        assertThat(UniqueTermSlugs.unique("news", false, Optional.empty(), Optional.empty(), tags))
                .isEqualTo("news-2");
    }

    @Test
    void theNumberIsChosenAgainstEveryTaxonomy() {
        Table categories = new Table("category")
                .add(2, "category", "News", "news", null)
                .add(3, "post_tag", "Other", "news-2", null);
        assertThat(UniqueTermSlugs.unique("news", true, Optional.empty(), Optional.empty(), categories))
                .isEqualTo("news-3");
    }

    @Test
    void aSlugTakenOnlyInAnotherTaxonomyIsKept() {
        Table categories = new Table("category").add(2, "post_tag", "News", "news", null);
        assertThat(UniqueTermSlugs.unique("news", true, Optional.empty(), Optional.empty(), categories))
                .isEqualTo("news");
    }

    @Test
    void aChildGetsItsParentsSlugFirst() {
        Table categories = new Table("category")
                .add(2, "category", "World", "world", null)
                .add(3, "category", "News", "news", null);
        assertThat(UniqueTermSlugs.unique("news", true, Optional.of(TermId.of(2)), Optional.empty(), categories))
                .isEqualTo("news-world");
    }

    @Test
    void ancestorsAreAppendedWhileTheResultExistsAnywhere() {
        Table categories = new Table("category")
                .add(2, "category", "World", "world", null)
                .add(3, "category", "News", "news", null)
                .add(4, "category", "Europe", "europe", 2L)
                .add(5, "post_tag", "News Europe", "news-europe", null);
        assertThat(UniqueTermSlugs.unique("news", true, Optional.of(TermId.of(4)), Optional.empty(), categories))
                .isEqualTo("news-europe-world");
        categories.add(6, "post_tag", "Taken", "news-europe-world", null);
        assertThat(UniqueTermSlugs.unique("news", true, Optional.of(TermId.of(4)), Optional.empty(), categories))
                .isEqualTo("news-europe-world-2");
    }

    @Test
    void aNameAnywhereCountsInTheParentProbe() {
        Table categories = new Table("category")
                .add(1, "category", "Earth", "earth", null)
                .add(2, "category", "World", "world", 1L)
                .add(3, "category", "News", "news", null);
        assertThat(UniqueTermSlugs.unique("news", true, Optional.of(TermId.of(2)), Optional.empty(), categories))
                .isEqualTo("news-world");
        categories.add(4, "post_tag", "News-World", "something-else", null);
        assertThat(UniqueTermSlugs.unique("news", true, Optional.of(TermId.of(2)), Optional.empty(), categories))
                .isEqualTo("news-world-earth");
    }

    @Test
    void aFlatTaxonomyNeverTakesParentSlugs() {
        Table tags = new Table("post_tag")
                .add(2, "post_tag", "World", "world", null)
                .add(3, "post_tag", "News", "news", null);
        assertThat(UniqueTermSlugs.unique("news", false, Optional.of(TermId.of(2)), Optional.empty(), tags))
                .isEqualTo("news-2");
    }

    @Test
    void anUpdatedTermDoesNotCollideWithItselfInTheFirstNumericCheck() {
        Table categories =
                new Table("category").add(2, "category", "A", "a", null).add(3, "category", "B", "a-a", null);
        assertThat(UniqueTermSlugs.unique("a", true, Optional.of(TermId.of(2)), Optional.of(TermId.of(3)), categories))
                .isEqualTo("a-a");
        assertThat(UniqueTermSlugs.unique("a", true, Optional.of(TermId.of(2)), Optional.of(TermId.of(9)), categories))
                .isEqualTo("a-a-2");
    }

    @Test
    void theNumericLoopDoesNotSkipTheTermBeingUpdated() {
        Table categories =
                new Table("category").add(2, "category", "A", "a", null).add(3, "category", "B", "a-2", null);
        assertThat(UniqueTermSlugs.unique("a", false, Optional.empty(), Optional.of(TermId.of(3)), categories))
                .isEqualTo("a-3");
    }

    @Test
    void aParentOfAnotherTaxonomyAddsNoSuffix() {
        Table categories = new Table("category")
                .add(2, "category", "News", "news", null)
                .add(3, "post_tag", "World", "world", null);
        assertThat(UniqueTermSlugs.unique("news", true, Optional.of(TermId.of(3)), Optional.empty(), categories))
                .isEqualTo("news-2");
    }
}
