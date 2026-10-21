package org.quarkpress.taxonomy.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.builder.BuildContext;
import io.quarkus.builder.BuildStep;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.core.deployment.spi.TaxonomyBuildItem;
import org.quarkpress.security.TaxonomyCapabilities;
import org.quarkpress.taxonomy.Taxonomies;
import org.quarkpress.taxonomy.Taxonomy;
import org.quarkpress.taxonomy.TermException;

class RegistriesDbTest {

    @Taxonomy(key = "zulu", objectTypes = "post")
    static class Zulu {}

    @Taxonomy(
            key = "genre",
            objectTypes = {"post", "page"},
            name = "Genres",
            singularName = "Genre",
            hierarchical = true,
            restBase = "genres",
            defaultTerm = "General",
            description = "Kinds of writing",
            labels = {"add_new_item=Add Genre"})
    static class Genre {}

    @Taxonomy(key = "mood", objectTypes = "post", publicly = false, sort = true)
    static class Mood {}

    @Taxonomy(key = "shade", objectTypes = "post", showUi = false)
    static class Shade {}

    @RegisterExtension
    static final QuarkusExtensionTest app = TaxonomyApp.with(
                    RegistriesDbTest.class, Zulu.class, Genre.class, Mood.class, Shade.class)
            .addBuildChainCustomizer(chain -> chain.addBuildStep(new BuildStep() {
                        @Override
                        public void execute(BuildContext context) {
                            context.produce(new TaxonomyBuildItem(TaxonomyDefinition.builder("lima", List.of("post"))
                                    .build()));
                        }
                    })
                    .produces(TaxonomyBuildItem.class)
                    .build());

    @Inject
    Taxonomies taxonomies;

    @Test
    void categoriesAreHierarchicalWithWordPresssArguments() {
        TaxonomyDefinition category = taxonomies.find("category").orElseThrow();
        assertThat(category.objectTypes()).containsExactly("post");
        assertThat(category.hierarchical()).isTrue();
        assertThat(category.publicly()).isTrue();
        assertThat(category.showUi()).isTrue();
        assertThat(category.showAdminColumn()).isTrue();
        assertThat(category.showInRest()).isTrue();
        assertThat(category.restBase()).isEqualTo(Optional.of("categories"));
        assertThat(category.builtIn()).isTrue();
        assertThat(category.labels())
                .containsEntry("name", "Categories")
                .containsEntry("name_admin_bar", "category")
                .containsEntry("template_name", "Category Archives");
        assertThat(taxonomies.capabilities("category")).contains(TaxonomyCapabilities.CATEGORY);
    }

    @Test
    void tagsAreFlatWithWordPresssArguments() {
        TaxonomyDefinition tag = taxonomies.find("post_tag").orElseThrow();
        assertThat(tag.objectTypes()).containsExactly("post");
        assertThat(tag.hierarchical()).isFalse();
        assertThat(tag.publicly()).isTrue();
        assertThat(tag.showUi()).isTrue();
        assertThat(tag.showInRest()).isTrue();
        assertThat(tag.builtIn()).isTrue();
        assertThat(tag.labels()).containsEntry("template_name", "Tag Archives");
        assertThat(tag.showAdminColumn()).isTrue();
        assertThat(tag.restBase()).isEqualTo(Optional.of("tags"));
        assertThat(tag.labels()).containsEntry("name", "Tags").containsEntry("name_admin_bar", "post_tag");
        assertThat(taxonomies.capabilities("post_tag")).contains(TaxonomyCapabilities.POST_TAG);
        assertThat(taxonomies.hierarchical("post_tag")).isFalse();
    }

    @Test
    void annotatedTaxonomiesFollowTheBuiltInsInKeyOrder() {
        assertThat(taxonomies.all().stream().map(TaxonomyDefinition::key))
                .containsExactly("category", "post_tag", "genre", "lima", "mood", "shade", "zulu");
        TaxonomyDefinition genre = taxonomies.find("genre").orElseThrow();
        assertThat(genre.objectTypes()).containsExactly("post", "page");
        assertThat(genre.labels())
                .containsEntry("name", "Genres")
                .containsEntry("singular_name", "Genre")
                .containsEntry("add_new_item", "Add Genre")
                .containsEntry("template_name", "Genre Archives");
        assertThat(genre.defaultTerm()).contains(new TaxonomyDefinition.DefaultTerm("General", "", ""));
        assertThat(genre.showInRest()).isTrue();
        assertThat(genre.restBase()).isEqualTo(Optional.of("genres"));
        assertThat(genre.description()).isEqualTo("Kinds of writing");
        assertThat(genre.hierarchical()).isTrue();
        assertThat(taxonomies.hierarchical("genre")).isTrue();
        assertThat(taxonomies.capabilities("genre")).contains(TaxonomyCapabilities.defaults("genre"));
        TaxonomyDefinition mood = taxonomies.find("mood").orElseThrow();
        assertThat(mood.publiclyQueryable()).isFalse();
        assertThat(mood.showUi()).isFalse();
        assertThat(mood.sort()).isTrue();
        assertThat(taxonomies.find("shade").orElseThrow().showUi()).isFalse();
        assertThat(taxonomies.find("shade").orElseThrow().publicly()).isTrue();
    }

    @Test
    void typesListTheirTaxonomiesInRegistrationOrder() {
        assertThat(taxonomies.forType("post").stream().map(TaxonomyDefinition::key))
                .containsExactly("category", "post_tag", "genre", "lima", "mood", "shade", "zulu");
        assertThat(taxonomies.forType("page").stream().map(TaxonomyDefinition::key))
                .containsExactly("genre");
        assertThat(taxonomies.forType("attachment")).isEmpty();
        assertThat(taxonomies.registered("nav_menu")).isFalse();
        assertThat(taxonomies.capabilities("nav_menu")).isEmpty();
        assertThat(taxonomies.require("post_tag").key()).isEqualTo("post_tag");
        assertThatThrownBy(() -> taxonomies.require("nav_menu"))
                .isInstanceOfSatisfying(TermException.class, refused -> {
                    assertThat(refused.code()).isEqualTo("invalid_taxonomy");
                    assertThat(refused.detail()).isEqualTo("nav_menu");
                })
                .hasMessage("Invalid taxonomy.");
    }
}
