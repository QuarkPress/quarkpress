package org.quarkword.front.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.quarkword.core.PostId;
import org.quarkword.core.SiteId;
import org.quarkword.front.QueryVars;
import org.quarkword.posts.PostSnapshot;

/** WordPress's numeric slug versus date archive resolution for the behaviour reference's examples. */
class NumericSlugConflictsTest {

    private static PostSnapshot post(String slug, String publishedAt, int pages) {
        Instant at = Instant.parse(publishedAt);
        return new PostSnapshot(
                PostId.of(18),
                SiteId.of(1),
                "post",
                "publish",
                slug,
                slug,
                "x" + "<!--nextpage-->y".repeat(pages - 1),
                "",
                Optional.empty(),
                Optional.empty(),
                0,
                Optional.of(at),
                at,
                at,
                "open",
                "open",
                "",
                false,
                "",
                Map.of(),
                1);
    }

    private static Function<String, Optional<PostSnapshot>> only(PostSnapshot post) {
        return path -> path.equals(post.slug()) ? Optional.of(post) : Optional.empty();
    }

    private static QueryVars resolve(String structure, Map<String, String> vars, PostSnapshot post) {
        return NumericSlugConflicts.resolve(
                QueryVars.of(vars),
                Structure.of(structure, "", ""),
                only(post),
                found -> NumericSlugConflicts.pages(found.content()),
                ZoneOffset.UTC);
    }

    @Test
    void aPostSlugThatLooksLikeAYear() {
        PostSnapshot post = post("2024", "2025-06-01T10:00:00Z", 1);
        assertThat(resolve("/%postname%/", Map.of("year", "2024"), post).asMap())
                .isEqualTo(Map.of("name", "2024", "page", "0"));
        // one page: a following number is the month, the archive wins
        assertThat(resolve("/%postname%/", Map.of("year", "2024", "monthnum", "3"), post)
                        .asMap())
                .isEqualTo(Map.of("year", "2024", "monthnum", "3"));
        PostSnapshot paged = post("2024", "2025-06-01T10:00:00Z", 3);
        assertThat(resolve("/%postname%/", Map.of("year", "2024", "monthnum", "3"), paged)
                        .asMap())
                .isEqualTo(Map.of("name", "2024", "page", "3"));
        assertThat(resolve("/%postname%/", Map.of("year", "2024", "monthnum", "4"), paged)
                        .asMap())
                .isEqualTo(Map.of("year", "2024", "monthnum", "4"));
        assertThat(resolve("/%postname%/", Map.of("year", "2023"), post).asMap())
                .isEqualTo(Map.of("year", "2023"));
    }

    @Test
    void aPostSlugThatLooksLikeAMonth() {
        PostSnapshot post = post("11", "2025-11-23T08:00:00Z", 2);
        assertThat(resolve("/%year%/%postname%/", Map.of("year", "2025", "monthnum", "11"), post)
                        .asMap())
                .isEqualTo(Map.of("name", "11", "page", "0"));
        assertThat(resolve("/%year%/%postname%/", Map.of("year", "2025", "monthnum", "11", "day", "2"), post)
                        .asMap())
                .isEqualTo(Map.of("name", "11", "page", "2"));
        assertThat(resolve("/%year%/%postname%/", Map.of("year", "2025", "monthnum", "11", "day", "3"), post)
                        .asMap())
                .isEqualTo(Map.of("year", "2025", "monthnum", "11", "day", "3"));
        // the post's year must be the requested one
        assertThat(resolve("/%year%/%postname%/", Map.of("year", "2024", "monthnum", "11"), post)
                        .asMap())
                .isEqualTo(Map.of("year", "2024", "monthnum", "11"));
    }

    @Test
    void aPostSlugThatLooksLikeADay() {
        PostSnapshot post = post("5", "2025-11-23T08:00:00Z", 1);
        assertThat(resolve("/%year%/%monthnum%/%postname%/", Map.of("year", "2025", "monthnum", "11", "day", "5"), post)
                        .asMap())
                .isEqualTo(Map.of("name", "5", "page", "0"));
        assertThat(resolve("/%year%/%monthnum%/%postname%/", Map.of("year", "2025", "monthnum", "10", "day", "5"), post)
                        .asMap())
                .isEqualTo(Map.of("year", "2025", "monthnum", "10", "day", "5"));
    }

    @Test
    void aCandidatePageOfZeroIsNoPage() {
        PostSnapshot single = post("2024", "2025-06-01T10:00:00Z", 1);
        assertThat(resolve("/%postname%/", Map.of("year", "2024", "monthnum", "0"), single)
                        .asMap())
                .isEqualTo(Map.of("name", "2024", "page", "0"));
        assertThat(resolve("/%postname%/", Map.of("year", "2024", "monthnum", "abc"), single)
                        .asMap())
                .isEqualTo(Map.of("name", "2024", "page", "0"));
        PostSnapshot paged = post("2024", "2025-06-01T10:00:00Z", 3);
        assertThat(resolve("/%postname%/", Map.of("year", "2024", "monthnum", "0"), paged)
                        .asMap())
                .isEqualTo(Map.of("name", "2024", "page", "0"));
        PostSnapshot month = post("11", "2025-11-23T08:00:00Z", 1);
        assertThat(resolve("/%year%/%postname%/", Map.of("year", "2025", "monthnum", "11", "day", "0"), month)
                        .asMap())
                .isEqualTo(Map.of("name", "11", "page", "0"));
    }

    @Test
    void onlyTheDateVariablesPresentAreCompared() {
        // reachable through the query string alone
        PostSnapshot month = post("11", "2025-11-23T08:00:00Z", 2);
        assertThat(resolve("/%year%/%postname%/", Map.of("monthnum", "11"), month)
                        .asMap())
                .isEqualTo(Map.of("name", "11", "page", "0"));
        PostSnapshot day = post("5", "2025-11-23T08:00:00Z", 1);
        assertThat(resolve("/%year%/%monthnum%/%postname%/", Map.of("day", "5"), day)
                        .asMap())
                .isEqualTo(Map.of("name", "5", "page", "0"));
        assertThat(resolve("/%year%/%monthnum%/%postname%/", Map.of("monthnum", "11", "day", "5"), day)
                        .asMap())
                .isEqualTo(Map.of("name", "5", "page", "0"));
        assertThat(resolve("/%year%/%monthnum%/%postname%/", Map.of("monthnum", "10", "day", "5"), day)
                        .asMap())
                .isEqualTo(Map.of("monthnum", "10", "day", "5"));
    }

    @Test
    void theDefaultStructureNeverCompares() {
        PostSnapshot post = post("2024", "2025-06-01T10:00:00Z", 1);
        assertThat(resolve("/%year%/%monthnum%/%day%/%postname%/", Map.of("year", "2024"), post)
                        .asMap())
                .isEqualTo(Map.of("year", "2024"));
        assertThat(resolve("/blog/%postname%/", Map.of("year", "2024"), post).asMap())
                .isEqualTo(Map.of("year", "2024"));
        assertThat(resolve("/%postname%/", Map.of("name", "x"), post).asMap()).isEqualTo(Map.of("name", "x"));
    }

    @Test
    void pagesCountTheSeparators() {
        assertThat(NumericSlugConflicts.pages("")).isEqualTo(1);
        assertThat(NumericSlugConflicts.pages("a<!--nextpage-->b<!--nextpage-->c"))
                .isEqualTo(3);
    }
}
