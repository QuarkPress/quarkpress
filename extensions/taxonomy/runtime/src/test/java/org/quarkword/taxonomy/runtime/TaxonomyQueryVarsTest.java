package org.quarkword.taxonomy.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkword.core.TaxonomyDefinition;

class TaxonomyQueryVarsTest {

    @Test
    void aGivenQueryVarIsSlugifiedAsWordPressDoes() {
        TaxonomyDefinition shelf = TaxonomyDefinition.builder("shelf", List.of("post"))
                .queryVar("Book Shelf")
                .build();
        assertThat(TaxonomyQueryVars.slugified(shelf).queryVar()).isEqualTo(Optional.of("book-shelf"));
        assertThat(TaxonomyQueryVars.slugified(shelf).rewrite()).isEqualTo(shelf.rewrite());
        TaxonomyDefinition vis = TaxonomyDefinition.builder("vis", List.of("post"))
                .queryVar("Vis Var")
                .build();
        assertThat(TaxonomyQueryVars.slugified(vis).queryVar()).isEqualTo(Optional.of("vis-var"));
    }

    @Test
    void aSlugOrAMissingQueryVarIsLeftAlone() {
        TaxonomyDefinition hidden = TaxonomyDefinition.builder("hidden", List.of("post"))
                .publicly(false)
                .build();
        assertThat(TaxonomyQueryVars.slugified(hidden)).isSameAs(hidden);
        for (TaxonomyDefinition taxonomy : BuiltInTaxonomies.all()) {
            assertThat(TaxonomyQueryVars.slugified(taxonomy)).as(taxonomy.key()).isSameAs(taxonomy);
        }
    }

    @Test
    void aQueryVarThatSlugifiesToNothingIsRefused() {
        TaxonomyDefinition bang = TaxonomyDefinition.builder("bang", List.of("post"))
                .queryVar("!!!")
                .build();
        assertThatThrownBy(() -> TaxonomyQueryVars.slugified(bang))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bang")
                .hasMessageContaining("!!!");
    }
}
