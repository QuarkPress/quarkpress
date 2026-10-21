package org.quarkpress.taxonomy.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.TaxonomyDefinition;

class TaxonomyJsonTest {

    @Test
    void everyDefinitionReadsBackEqual() {
        TaxonomyDefinition genre = TaxonomyDefinition.builder("genre", List.of("post", "book"))
                .hierarchical(true)
                .labels(Map.of("name", "Genres", "singular_name", "Genre"))
                .description("Kinds of books")
                .publiclyQueryable(false)
                .showAdminColumn(true)
                .capabilities(Map.of("assign_terms", "edit_books"))
                .showInRest(true)
                .restBase("genres")
                .defaultTerm("General", "general", "Everything else")
                .sort(true)
                .build();
        TaxonomyDefinition mixed = TaxonomyDefinition.builder("mixed", List.of("page"))
                .publicly(false)
                .publiclyQueryable(true)
                .showUi(true)
                .showInMenu(false)
                .showInNavMenus(true)
                .showTagcloud(false)
                .showInQuickEdit(true)
                .build();
        TaxonomyDefinition hidden = TaxonomyDefinition.builder("hidden", List.of("post"))
                .publicly(false)
                .showUi(false)
                .showInNavMenus(false)
                .showTagcloud(false)
                .showInQuickEdit(false)
                .build();
        List<TaxonomyDefinition> all =
                List.of(BuiltInTaxonomies.all().get(0), BuiltInTaxonomies.all().get(1), genre, mixed, hidden);
        assertThat(mixed.showInMenu()).isFalse();
        assertThat(mixed.showInNavMenus()).isTrue();
        assertThat(mixed.showTagcloud()).isFalse();
        assertThat(mixed.showInQuickEdit()).isTrue();
        assertThat(mixed.publiclyQueryable()).isTrue();
        assertThat(TaxonomyJson.read(TaxonomyJson.write(all))).isEqualTo(all);
    }
}
