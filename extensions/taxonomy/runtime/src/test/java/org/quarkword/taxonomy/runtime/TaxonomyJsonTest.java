package org.quarkword.taxonomy.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.quarkword.core.TaxonomyDefinition;

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
        TaxonomyDefinition topic = TaxonomyDefinition.builder("topic", List.of("post"))
                .hierarchical(true)
                .queryVar("subject")
                .rewrite("topics", false, true)
                .build();
        List<TaxonomyDefinition> all = new ArrayList<>(BuiltInTaxonomies.all());
        all.addAll(List.of(genre, mixed, hidden, topic));
        assertThat(mixed.showInMenu()).isFalse();
        assertThat(mixed.showInNavMenus()).isTrue();
        assertThat(mixed.showTagcloud()).isFalse();
        assertThat(mixed.showInQuickEdit()).isTrue();
        assertThat(mixed.publiclyQueryable()).isTrue();
        assertThat(mixed.queryVar()).contains("mixed");
        assertThat(genre.queryVar()).isEmpty();
        assertThat(genre.rewrite()).contains(new TaxonomyDefinition.Rewrite("genre", true, false));
        assertThat(hidden.queryVar()).isEmpty();
        assertThat(hidden.rewrite()).isEmpty();
        assertThat(topic.queryVar()).contains("subject");
        assertThat(topic.rewrite()).contains(new TaxonomyDefinition.Rewrite("topics", false, true));

        String json = TaxonomyJson.write(all);
        assertThat(json)
                .contains("\"queryVar\":\"category_name\"")
                .contains("\"queryVar\":\"subject\"")
                .contains("\"rewrite\":{\"slug\":\"topics\",\"withFront\":false,\"hierarchical\":true}");
        assertThat(TaxonomyJson.read(json)).isEqualTo(all);
    }

    @Test
    void builtInTaxonomiesCarryWordPresssQueryVarsAndRewriteSlugs() {
        List<TaxonomyDefinition> all = BuiltInTaxonomies.all();
        TaxonomyDefinition category = all.get(0);
        TaxonomyDefinition tag = all.get(1);
        TaxonomyDefinition format = all.get(2);
        TaxonomyDefinition patterns = all.get(3);
        assertThat(category.queryVar()).contains("category_name");
        assertThat(category.rewrite()).contains(new TaxonomyDefinition.Rewrite("category", true, true));
        assertThat(tag.queryVar()).contains("tag");
        assertThat(tag.rewrite()).contains(new TaxonomyDefinition.Rewrite("tag", true, false));
        assertThat(format.queryVar()).contains("post_format");
        assertThat(format.rewrite()).contains(new TaxonomyDefinition.Rewrite("type", true, false));
        assertThat(patterns.key()).isEqualTo("wp_pattern_category");
        assertThat(patterns.queryVar()).isEmpty();
        assertThat(patterns.rewrite()).isEmpty();
    }
}
