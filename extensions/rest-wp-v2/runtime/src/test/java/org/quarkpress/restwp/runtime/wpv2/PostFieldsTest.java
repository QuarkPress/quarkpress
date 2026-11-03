package org.quarkpress.restwp.runtime.wpv2;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.PostId;
import org.quarkpress.core.PostTypeDefinition;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TermId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.taxonomy.TermSnapshot;

/** The front-end class list of a post as WordPress's REST answers list it. */
class PostFieldsTest {

    private static final Instant WHEN = Instant.parse("2026-01-10T10:00:00Z");

    private static final PostRoutes POSTS =
            routes(PostTypeDefinition.builder("post").publicly(true).builtIn().build());

    private static final PostRoutes PAGES = routes(PostTypeDefinition.builder("page")
            .publicly(true)
            .hierarchical(true)
            .builtIn()
            .build());

    private static PostRoutes routes(PostTypeDefinition type) {
        return new PostRoutes(
                type,
                "wp/v2",
                type.key() + "s",
                PostSchema.of(type, List.of(), JsonNodeFactory.instance.objectNode(), List.of("publish", "draft")),
                List.of());
    }

    private static PostSnapshot post(long id, String type, String status, String password) {
        return new PostSnapshot(
                PostId.of(id),
                SiteId.of(1),
                type,
                status,
                "slug-" + id,
                "Title",
                "",
                "",
                Optional.of(UserId.of(2)),
                Optional.empty(),
                0,
                Optional.of(WHEN),
                WHEN,
                WHEN,
                "open",
                "open",
                password,
                false,
                "guid-" + id,
                Map.of(),
                0);
    }

    private static PostSnapshot published(long id, String type) {
        return post(id, type, "publish", "");
    }

    private static TermSnapshot term(long id, String taxonomy, String slug) {
        return new TermSnapshot(TermId.of(id), SiteId.of(1), taxonomy, slug, slug, "", Optional.empty(), 1, 0);
    }

    @Test
    void classListFollowsWordPress() {
        assertThat(PostFields.classList(
                        published(28, "post"),
                        POSTS,
                        "standard",
                        false,
                        false,
                        Map.of("category", List.of(term(1, "category", "uncategorized")))))
                .containsExactly(
                        "post-28",
                        "post",
                        "type-post",
                        "status-publish",
                        "format-standard",
                        "hentry",
                        "category-uncategorized");
    }

    @Test
    void formatsAndNumericSlugsUseWordPressNames() {
        // the format taxonomy's term slug is post-format-aside: its class is prefixed with the taxonomy; a tag whose
        // slug reads as a number is named by its id; a post without a format term is standard
        Map<String, List<TermSnapshot>> terms = new LinkedHashMap<>();
        terms.put("category", List.of(term(1, "category", "uncategorized"), term(7, "category", "news")));
        terms.put(
                "post_tag",
                List.of(term(41, "post_tag", "123"), term(42, "post_tag", "1e3"), term(43, "post_tag", "")));
        terms.put("post_format", List.of(term(9, "post_format", "post-format-aside")));
        assertThat(PostFields.classList(published(30, "post"), POSTS, "aside", false, false, terms))
                .containsExactly(
                        "post-30",
                        "post",
                        "type-post",
                        "status-publish",
                        "format-aside",
                        "hentry",
                        "category-uncategorized",
                        "category-news",
                        "tag-41",
                        "tag-42",
                        "tag-43",
                        "post_format-post-format-aside");
        assertThat(PostFields.classList(published(31, "post"), POSTS, "", false, false, Map.of()))
                .containsExactly("post-31", "post", "type-post", "status-publish", "format-standard", "hentry");
        assertThat(PostFields.phpNumeric("12e")).isFalse();
        assertThat(PostFields.phpNumeric("e12")).isFalse();
        assertThat(PostFields.phpNumeric("1e-3")).isTrue();
        assertThat(PostFields.phpNumeric("1-2")).isFalse();
    }

    @Test
    void aPasswordIsRequiredWhateverTheRequestLifted() {
        // WordPress keeps post-password-required after ?password=s3 and in the edit context: the class list only
        // says whether a password is set; a featured image is not listed while a password is required
        PostSnapshot protectedPost = post(87, "post", "publish", "s3");
        List<String> expected = List.of(
                "post-87",
                "post",
                "type-post",
                "status-publish",
                "format-standard",
                "post-password-required",
                "hentry",
                "category-uncategorized");
        Map<String, List<TermSnapshot>> terms = Map.of("category", List.of(term(1, "category", "uncategorized")));
        assertThat(PostFields.classList(protectedPost, POSTS, "standard", true, true, terms))
                .containsExactlyElementsOf(expected);
        assertThat(PostFields.classList(published(88, "post"), POSTS, "standard", false, true, Map.of()))
                .containsExactly(
                        "post-88",
                        "post",
                        "type-post",
                        "status-publish",
                        "format-standard",
                        "has-post-thumbnail",
                        "hentry");
    }

    @Test
    void pagesHaveNoFormatAndStatusesAreListedAsStored() {
        assertThat(PostFields.classList(post(5, "page", "draft", ""), PAGES, "", false, false, Map.of()))
                .containsExactly("post-5", "page", "type-page", "status-draft", "hentry");
    }

    @Test
    void duplicateClassesAreListedOnce() {
        // a taxonomy named like a term prefix could repeat a class: each is kept once, at its first place
        Map<String, List<TermSnapshot>> terms = new LinkedHashMap<>();
        terms.put("category", List.of(term(1, "category", "news"), term(2, "category", "news")));
        assertThat(PostFields.classList(published(6, "post"), POSTS, "standard", false, false, terms))
                .containsExactly(
                        "post-6", "post", "type-post", "status-publish", "format-standard", "hentry", "category-news");
    }
}
