package org.quarkpress.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TaxonomyDefinitionTest {

    private static String cp(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    @Test
    void aRegistrationWithoutArgumentsIsPublicAndFlat() {
        TaxonomyDefinition mood =
                TaxonomyDefinition.builder("mood", List.of("post")).build();
        assertThat(mood.publicly()).isTrue();
        assertThat(mood.publiclyQueryable()).isTrue();
        assertThat(mood.hierarchical()).isFalse();
        assertThat(mood.showUi()).isTrue();
        assertThat(mood.showInMenu()).isTrue();
        assertThat(mood.showInNavMenus()).isTrue();
        assertThat(mood.showTagcloud()).isTrue();
        assertThat(mood.showInQuickEdit()).isTrue();
        assertThat(mood.showAdminColumn()).isFalse();
        assertThat(mood.showInRest()).isFalse();
        assertThat(mood.restBase()).isEmpty();
        assertThat(mood.defaultTerm()).isEmpty();
        assertThat(mood.sort()).isFalse();
        assertThat(mood.builtIn()).isFalse();
        assertThat(mood.capabilities())
                .containsExactly(
                        Map.entry("manage_terms", "manage_categories"),
                        Map.entry("edit_terms", "manage_categories"),
                        Map.entry("delete_terms", "manage_categories"),
                        Map.entry("assign_terms", "edit_posts"));
        assertThat(mood.labels())
                .containsEntry("name", "Tags")
                .containsEntry("singular_name", "Tag")
                .containsEntry("all_items", "All Tags")
                .containsEntry("popular_items", "Popular Tags")
                .containsEntry("separate_items_with_commas", "Separate tags with commas")
                .containsEntry("menu_name", "Tags")
                .containsEntry("name_admin_bar", "mood")
                .containsEntry("template_name", "Tag Archives")
                .containsEntry("back_to_items", "&larr; Go to Tags")
                .doesNotContainKeys(
                        "parent_item", "parent_item_colon", "parent_field_description", "filter_by_item", "archives");
    }

    @Test
    void aPrivateTaxonomyHidesItsInterface() {
        TaxonomyDefinition hidden = TaxonomyDefinition.builder("hidden", List.of("post"))
                .publicly(false)
                .build();
        assertThat(hidden.publiclyQueryable()).isFalse();
        assertThat(hidden.showUi()).isFalse();
        assertThat(hidden.showInMenu()).isFalse();
        assertThat(hidden.showInNavMenus()).isFalse();
        assertThat(hidden.showTagcloud()).isFalse();
        assertThat(hidden.showInQuickEdit()).isFalse();
    }

    @Test
    void theMenuFollowsAHiddenInterfaceEvenWhenAsked() {
        TaxonomyDefinition shown = TaxonomyDefinition.builder("vis", List.of("post"))
                .showUi(false)
                .showInMenu(true)
                .build();
        assertThat(shown.publicly()).isTrue();
        assertThat(shown.showInMenu()).isFalse();
        assertThat(shown.showTagcloud()).isFalse();
        assertThat(shown.showInQuickEdit()).isFalse();
        assertThat(shown.showInNavMenus()).isTrue();
    }

    @Test
    void hierarchicalTaxonomiesUseTheCategoryWording() {
        TaxonomyDefinition topic = TaxonomyDefinition.builder("topic", List.of("post"))
                .hierarchical(true)
                .build();
        assertThat(topic.labels())
                .containsEntry("name", "Categories")
                .containsEntry("parent_item", "Parent Category")
                .containsEntry("parent_item_colon", "Parent Category:")
                .containsEntry("filter_by_item", "Filter by category")
                .containsEntry("template_name", "Category Archives")
                .doesNotContainKeys(
                        "popular_items", "separate_items_with_commas", "add_or_remove_items", "choose_from_most_used");
    }

    @Test
    void aNameAloneNamesEveryDerivedLabel() {
        TaxonomyDefinition genre = TaxonomyDefinition.builder("genre", List.of("book"))
                .hierarchical(true)
                .labels(Map.of("name", "Genres"))
                .build();
        assertThat(genre.labels())
                .containsEntry("singular_name", "Genres")
                .containsEntry("all_items", "Genres")
                .containsEntry("menu_name", "Genres")
                .containsEntry("archives", "Genres")
                .containsEntry("name_admin_bar", "Genres")
                .containsEntry("template_name", "Genres Archives")
                .containsEntry("edit_item", "Edit Category");
    }

    @Test
    void theLegacyLabelFillsAnEmptyNameAndAGivenTemplateNameStays() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("name", "");
        labels.put("template_name", "Mood board");
        TaxonomyDefinition mood = TaxonomyDefinition.builder("mood", List.of("post"))
                .label("Moods")
                .labels(labels)
                .build();
        assertThat(mood.labels())
                .containsEntry("name", "Moods")
                .containsEntry("singular_name", "Moods")
                .containsEntry("template_name", "Mood board");
    }

    @Test
    void keysAreNotSanitizedButLimitedTo32Bytes() {
        assertThat(TaxonomyDefinition.builder("Mood Tag", List.of("book"))
                        .build()
                        .key())
                .isEqualTo("Mood Tag");
        assertThat(TaxonomyDefinition.builder("x".repeat(32), List.of()).build().key())
                .hasSize(32);
        for (String key : List.of("", "x".repeat(33), cp(0xE9).repeat(17))) {
            assertThatThrownBy(() -> TaxonomyDefinition.builder(key, List.of()).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageEndingWith("Taxonomy names must be between 1 and 32 characters in length.");
        }
    }

    @Test
    void objectTypesAreKeptOnceInOrder() {
        assertThat(TaxonomyDefinition.builder("t", List.of("book", "book", "page"))
                        .build()
                        .objectTypes())
                .containsExactly("book", "page");
        assertThat(TaxonomyDefinition.builder("t", List.of("book", "page"))
                        .build()
                        .attachedTo("page"))
                .isTrue();
        assertThat(TaxonomyDefinition.builder("t", List.of("book")).build().attachedTo("post"))
                .isFalse();
    }

    @Test
    void aDefaultTermGivenAsANameHasNoSlugOrDescription() {
        TaxonomyDefinition genre = TaxonomyDefinition.builder("genre", List.of("post"))
                .showInRest(true)
                .defaultTerm("General")
                .build();
        assertThat(genre.defaultTerm()).contains(new TaxonomyDefinition.DefaultTerm("General", "", ""));
        assertThat(TaxonomyDefinition.builder("g", List.of())
                        .defaultTerm("General", "gen", "All of it")
                        .build()
                        .defaultTerm())
                .contains(new TaxonomyDefinition.DefaultTerm("General", "gen", "All of it"));
    }

    @Test
    void givenCapabilitiesReplaceTheDefaultsOneByOne() {
        TaxonomyDefinition tag = TaxonomyDefinition.builder("post_tag", List.of("post"))
                .capabilities(Map.of("edit_terms", "edit_post_tags", "assign_terms", "assign_post_tags"))
                .build();
        assertThat(tag.capability("manage_terms")).isEqualTo("manage_categories");
        assertThat(tag.capability("edit_terms")).isEqualTo("edit_post_tags");
        assertThat(tag.capability("assign_terms")).isEqualTo("assign_post_tags");
        assertThatThrownBy(() -> tag.capability("publish_terms"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("not a taxonomy capability: publish_terms");
    }

    @Test
    void restBaseAndSortAreKept() {
        TaxonomyDefinition series = TaxonomyDefinition.builder("series", List.of("post"))
                .showInRest(true)
                .restBase("series-list")
                .sort(true)
                .builtIn()
                .build();
        assertThat(series.restBase()).isEqualTo(Optional.of("series-list"));
        assertThat(series.sort()).isTrue();
        assertThat(series.builtIn()).isTrue();
    }
}
