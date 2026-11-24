package org.quarkword.front.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.quarkword.core.PostTypeDefinition;
import org.quarkword.core.TaxonomyDefinition;

/** WordPress 7.1.3's rewrite state for a permalink structure, recorded with PHP. */
class StructureTest {

    private static final String DEFAULT = "/%year%/%monthnum%/%day%/%postname%/";

    private static Structure of(String structure) {
        return Structure.of(structure, "", "");
    }

    @Test
    void theDefaultStructure() {
        Structure structure = of(DEFAULT);
        assertThat(structure.plain()).isFalse();
        assertThat(structure.front()).isEqualTo("/");
        assertThat(structure.trailingSlashes()).isTrue();
        assertThat(structure.verbosePageRules()).isFalse();
        assertThat(structure.root()).isEmpty();
        assertThat(structure.pathinfo()).isFalse();
        assertThat(structure.tags()).containsExactly("%year%", "%monthnum%", "%day%", "%postname%");
        assertThat(structure.postnameFirst()).isFalse();
        assertThat(structure.dateStructure()).contains("/%year%/%monthnum%/%day%");
        assertThat(structure.yearStructure()).contains("/%year%/");
        assertThat(structure.monthStructure()).contains("/%year%/%monthnum%/");
        assertThat(structure.authorStructure()).contains("/author/%author%");
        assertThat(structure.searchStructure()).contains("search/%search%");
        assertThat(structure.pageStructure()).contains("%pagename%");
        assertThat(structure.categoryStructure()).contains("/category/%category%");
        assertThat(structure.tagStructure()).contains("/tag/%post_tag%");
        assertThat(structure.postFormatStructure()).contains("/type/%post_format%");
    }

    @Test
    void aFrontWithoutTrailingSlash() {
        Structure structure = of("/blog/%postname%");
        assertThat(structure.front()).isEqualTo("/blog/");
        assertThat(structure.trailingSlashes()).isFalse();
        assertThat(structure.verbosePageRules()).isTrue();
        assertThat(structure.postnameFirst()).isTrue();
        assertThat(structure.categoryStructure()).contains("/blog/category/%category%");
        assertThat(structure.tagStructure()).contains("/blog/tag/%post_tag%");
        assertThat(structure.authorStructure()).contains("/blog/author/%author%");
        assertThat(structure.dateStructure()).contains("/blog/%year%/%monthnum%/%day%");
        assertThat(structure.yearStructure()).contains("/blog/%year%/");
        assertThat(structure.monthStructure()).contains("/blog/%year%/%monthnum%/");
        assertThat(structure.searchStructure()).contains("search/%search%");
        assertThat(structure.pageStructure()).contains("%pagename%");
        assertThat(structure.postFormatStructure()).contains("/blog/type/%post_format%");
    }

    @Test
    void basesLeaveTheFront() {
        Structure structure = Structure.of("/blog/%postname%/", "topics", "labels");
        assertThat(structure.categoryStructure()).contains("topics/%category%");
        assertThat(structure.tagStructure()).contains("labels/%post_tag%");
        assertThat(Structure.of(DEFAULT, "topics", "").categoryStructure()).contains("topics/%category%");
    }

    @Test
    void aPostIdAmongTheFirstTagsMovesDateArchivesUnderDate() {
        Structure structure = of("/%post_id%/%postname%/");
        assertThat(structure.verbosePageRules()).isFalse();
        assertThat(structure.dateStructure()).contains("/date/%year%/%monthnum%/%day%");
        assertThat(structure.yearStructure()).contains("/date/%year%/");
        assertThat(structure.monthStructure()).contains("/date/%year%/%monthnum%/");
        assertThat(of("/%year%/%post_id%/%monthnum%/%postname%/").dateStructure())
                .contains("/date/%year%/%monthnum%/%day%");
        assertThat(of("/%year%/%monthnum%/%day%/%post_id%/").dateStructure()).contains("/%year%/%monthnum%/%day%");
    }

    @Test
    void pathinfoStructuresHaveAnIndexRoot() {
        Structure structure = of("/index.php/%postname%/");
        assertThat(structure.root()).isEqualTo("index.php/");
        assertThat(structure.pathinfo()).isTrue();
        assertThat(structure.front()).isEqualTo("/index.php/");
        assertThat(structure.verbosePageRules()).isTrue();
        assertThat(structure.searchStructure()).contains("index.php/search/%search%");
        assertThat(structure.pageStructure()).contains("index.php/%pagename%");
        assertThat(structure.authorStructure()).contains("/index.php/author/%author%");
        assertThat(structure.dateStructure()).contains("/index.php/%year%/%monthnum%/%day%");
        assertThat(structure.categoryStructure()).contains("/index.php/category/%category%");
        assertThat(Structure.of("/index.php/%postname%/", "topics", "").categoryStructure())
                .contains("/index.php/topics/%category%");
        assertThat(of("//index.php/%postname%/").root()).isEqualTo("index.php/");
    }

    @Test
    void theDateOrderFollowsTheStructure() {
        Structure structure = of("/%day%/%monthnum%/%year%/%postname%/");
        assertThat(structure.dateStructure()).contains("/%day%/%monthnum%/%year%");
        assertThat(structure.yearStructure()).contains("/%year%");
        assertThat(structure.monthStructure()).contains("/%monthnum%/%year%");
        Structure american = of("/%monthnum%/%day%/%year%/%postname%/");
        assertThat(american.dateStructure()).contains("/%monthnum%/%day%/%year%");
        assertThat(american.yearStructure()).contains("/%year%");
        assertThat(american.monthStructure()).contains("/%monthnum%/%year%");
    }

    @Test
    void verbosePageRulesForEachFirstTag() {
        assertThat(of("/%postname%/").verbosePageRules()).isTrue();
        assertThat(of("/%category%/%postname%/").verbosePageRules()).isTrue();
        assertThat(of("/%author%/%year%/%postname%/").verbosePageRules()).isTrue();
        assertThat(of("/%tag%/%postname%/").verbosePageRules()).isTrue();
        assertThat(of("/%post_tag%/%postname%/").verbosePageRules()).isFalse();
        assertThat(of("/%year%/%postname%/").verbosePageRules()).isFalse();
    }

    @Test
    void plainModeHasNoStructures() {
        Structure structure = Structure.of("", "topics", "labels");
        assertThat(structure.plain()).isTrue();
        assertThat(structure.front()).isEmpty();
        assertThat(structure.root()).isEmpty();
        assertThat(structure.trailingSlashes()).isFalse();
        assertThat(structure.verbosePageRules()).isFalse();
        assertThat(structure.tags()).isEmpty();
        assertThat(structure.postnameFirst()).isFalse();
        assertThat(structure.dateStructure()).isEmpty();
        assertThat(structure.yearStructure()).isEmpty();
        assertThat(structure.monthStructure()).isEmpty();
        assertThat(structure.authorStructure()).isEmpty();
        assertThat(structure.searchStructure()).isEmpty();
        assertThat(structure.pageStructure()).isEmpty();
        assertThat(structure.categoryStructure()).isEmpty();
        assertThat(structure.tagStructure()).isEmpty();
        assertThat(structure.postFormatStructure()).isEmpty();
        assertThat(structure.taxonomyStructure(genre(true))).isEmpty();
        assertThat(structure.postTypeStructure(book(true, true))).isEmpty();
        assertThat(structure.postTypeArchive(book(true, true))).isEmpty();
        assertThat(structure.trailing("http://wp.test/a/")).isEqualTo("http://wp.test/a");
    }

    @Test
    void trailingFollowsTheStructure() {
        assertThat(of(DEFAULT).trailing("http://wp.test/a")).isEqualTo("http://wp.test/a/");
        assertThat(of(DEFAULT).trailing("http://wp.test/a//")).isEqualTo("http://wp.test/a/");
        assertThat(of("/%postname%").trailing("http://wp.test/a/")).isEqualTo("http://wp.test/a");
        assertThat(of("/%postname%").trailing("http://wp.test/a//")).isEqualTo("http://wp.test/a");
        assertThat(of("/%postname%").trailing("http://wp.test/")).isEqualTo("http://wp.test");
    }

    @Test
    void taxonomyAndPostTypeStructuresFollowWithFront() {
        Structure structure = of("/blog/%postname%/");
        assertThat(structure.taxonomyStructure(genre(true))).contains("/blog/genre/%genre%");
        assertThat(structure.taxonomyStructure(genre(false))).contains("genre/%genre%");
        assertThat(structure.taxonomyStructure(category())).contains("/blog/category/%category%");
        assertThat(structure.postTypeStructure(book(true, true))).contains("/blog/books/%book%");
        assertThat(structure.postTypeStructure(book(false, true))).contains("books/%book%");
        assertThat(structure.postTypeArchive(book(true, true))).contains("blog/books");
        assertThat(structure.postTypeArchive(book(false, true))).contains("books");
        assertThat(structure.postTypeArchive(book(true, false))).isEmpty();
        assertThat(structure.postTypeArchive(library())).contains("blog/books");
        assertThat(structure.postTypeStructure(library())).contains("/blog/library/%book%");
        assertThat(structure.postTypeStructure(noRewrite())).isEmpty();
        assertThat(structure.postTypeArchive(noRewrite())).isEmpty();
        Structure pathinfo = of("/index.php/%postname%/");
        assertThat(pathinfo.postTypeArchive(book(true, true))).contains("index.php/books");
        assertThat(pathinfo.postTypeArchive(book(false, true))).contains("index.php/books");
        assertThat(pathinfo.taxonomyStructure(genre(false))).contains("index.php/genre/%genre%");
    }

    @Test
    void tagsIgnoreStrayPercentSigns() {
        assertThat(of("/%year%/100%/%postname%/").tags()).containsExactly("%year%", "%/%");
        assertThat(of("/50%").tags()).isEmpty();
        assertThat(of("/50%").front()).isEqualTo("/50");
        assertThat(of("/blog/").front()).isEmpty();
        assertThat(of("/blog/").tags()).isEmpty();
        assertThat(of("/blog/").plain()).isFalse();
        assertThat(of("/blog/").authorStructure()).contains("author/%author%");
    }

    private static TaxonomyDefinition genre(boolean withFront) {
        return TaxonomyDefinition.builder("genre", List.of("book"))
                .rewrite("genre", withFront, false)
                .build();
    }

    private static TaxonomyDefinition category() {
        return TaxonomyDefinition.builder("category", List.of("post"))
                .hierarchical(true)
                .rewrite("category", true, true)
                .build();
    }

    private static PostTypeDefinition book(boolean withFront, boolean archive) {
        return PostTypeDefinition.builder("book")
                .publicly(true)
                .hasArchive(archive)
                .rewrite("books", withFront, true, true)
                .build();
    }

    private static PostTypeDefinition library() {
        return PostTypeDefinition.builder("book")
                .publicly(true)
                .hasArchive("books")
                .rewrite("library", true, true, true)
                .build();
    }

    private static PostTypeDefinition noRewrite() {
        return PostTypeDefinition.builder("book")
                .publicly(true)
                .hasArchive(true)
                .rewrite(false)
                .build();
    }
}
