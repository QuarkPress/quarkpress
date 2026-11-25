package org.quarkword.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.PostId;
import org.quarkword.core.SiteId;
import org.quarkword.posts.PostChanges;
import org.quarkword.posts.PostLookups;
import org.quarkword.posts.PostLookups.DateParts;
import org.quarkword.posts.PostSnapshot;
import org.quarkword.posts.Posts;

/**
 * The front end's lookups: a slug prefix in slug order (as WordPress's database answers through its slug index: a
 * {@code /jav/} request finds {@code java-17} before {@code java-21}, front-facts G4), and old slugs and days recorded
 * by updates, found whatever the post's status now is.
 */
class PostLookupsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(PostLookupsDbTest.class, TestClock.class);

    static final List<String> VIEWABLE_TYPES = List.of("post", "page", "attachment");
    static final List<String> PUBLISHED = List.of("publish");

    @Inject
    @DataSource("quarkword")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    @Inject
    PostLookups lookups;

    @Inject
    TestClock clock;

    SiteId site;

    @BeforeEach
    void setUp() {
        clock.set(Instant.parse("2026-11-25T10:00:00Z"));
        site = TestData.siteIn(dataSource, "Europe/Paris");
    }

    private <T> T onSite(Callable<T> work) throws Exception {
        return Identities.onSite(site, Identities.system(), work);
    }

    private PostSnapshot post(String type, String status, String slug, String date) throws Exception {
        return onSite(() -> posts.create(PostChanges.builder()
                .type(type)
                .title(slug)
                .slug(slug)
                .status(status)
                .date(LocalDateTime.parse(date.replace(' ', 'T')))
                .build()));
    }

    private PostSnapshot update(PostId post, PostChanges changes) throws Exception {
        return onSite(() -> posts.update(post, changes));
    }

    private Optional<PostId> prefix(String prefix, DateParts date) throws Exception {
        return onSite(() -> lookups.firstBySlugPrefix(VIEWABLE_TYPES, PUBLISHED, prefix, date));
    }

    @Test
    void aSlugPrefixNarrowedByTheDateParts() throws Exception {
        PostSnapshot hello = post("post", "publish", "hello-world", "2025-11-20 10:15:30");
        post("post", "publish", "hello-again", "2025-12-01 10:00:00");
        // the local day decides: 00:30 in Paris on the 21st is the 20th in UTC
        PostSnapshot late = post("post", "publish", "hello-late", "2025-11-21 00:30:00");

        assertThat(prefix("hello", DateParts.of(2025, 11, 20))).contains(hello.id());
        assertThat(prefix("hello", DateParts.of(2025, 11, 21))).contains(late.id());
        assertThat(prefix("hello", DateParts.of(2025, 11, 22))).isEmpty();
        assertThat(prefix("hello", DateParts.of(2024, 0, 0))).isEmpty();
        // every part is compared on its own: the 20th of any month
        assertThat(prefix("hello-w", DateParts.of(0, 0, 20))).contains(hello.id());
        // the whole slug is a prefix of itself; a longer text is not
        assertThat(prefix("hello-world", DateParts.NONE)).contains(hello.id());
        assertThat(prefix("hello-world-2", DateParts.NONE)).isEmpty();
    }

    @Test
    void theFirstInSlugOrderWins() throws Exception {
        PostSnapshot java21 = post("post", "publish", "java-21", "2025-11-20 08:00:00");
        PostSnapshot java17 = post("post", "publish", "java-17", "2025-11-21 08:00:00");
        assertThat(java21.id().value()).isLessThan(java17.id().value());

        assertThat(prefix("jav", DateParts.NONE)).contains(java17.id());
        assertThat(prefix("java-2", DateParts.NONE)).contains(java21.id());
    }

    @Test
    void theSlugIndexOrderIsMysqlsCollationOrder() throws Exception {
        // WordPress's slug index (post_name(191), utf8mb4_unicode_520_ci) reads _ < - < % < digits < letters, case
        // folded, and only the first 191 characters, ties going to the lowest id (mysql:9.5 range scan)
        PostSnapshot java = post("post", "publish", "java", "2025-11-20 08:00:00");
        PostSnapshot jav1 = post("post", "publish", "jav1", "2025-11-20 08:00:00");
        PostSnapshot cyrillic = post("post", "publish", "jav%d0%ba", "2025-11-20 08:00:00");
        PostSnapshot dash = post("post", "publish", "jav-2", "2025-11-20 08:00:00");
        PostSnapshot underscore = post("post", "publish", "jav_1", "2025-11-20 08:00:00");
        assertThat(List.of(java, jav1, cyrillic, dash, underscore))
                .extracting(PostSnapshot::slug)
                .containsExactly("java", "jav1", "jav%d0%ba", "jav-2", "jav_1");

        assertThat(prefix("jav", DateParts.NONE)).contains(underscore.id());
        assertThat(prefix("jav-", DateParts.NONE)).contains(dash.id());
        assertThat(prefix("jav%", DateParts.NONE)).contains(cyrillic.id());
        assertThat(prefix("jav1", DateParts.NONE)).contains(jav1.id());

        String common = "t".repeat(188);
        PostSnapshot b = post("post", "publish", "lon" + common + "b", "2025-11-20 08:00:00");
        PostSnapshot a = post("post", "publish", "lon" + common + "a", "2025-11-20 08:00:00");
        assertThat(b.slug()).hasSize(192);
        assertThat(a.slug()).hasSize(192);
        // the slugs differ after the 191st character only: the index cannot tell them apart, the lower id wins
        assertThat(prefix("lon", DateParts.NONE)).contains(b.id());
    }

    @Test
    void onlyTheGivenTypesAndStatusesAndTheExactPrefix() throws Exception {
        post("post", "draft", "draft-post", "2025-11-20 08:00:00");
        post("post", "private", "secret", "2025-11-20 08:00:00");
        PostSnapshot second = post("post", "publish", "second-post", "2025-11-23 08:00:00");
        PostSnapshot page = post("page", "publish", "about", "2025-11-20 08:00:00");
        post("post", "publish", "50-percent", "2025-11-20 08:00:00");

        assertThat(prefix("dra", DateParts.NONE)).isEmpty();
        assertThat(prefix("sec", DateParts.NONE)).contains(second.id());
        assertThat(prefix("abou", DateParts.NONE)).contains(page.id());
        assertThat(onSite(() -> lookups.firstBySlugPrefix(List.of("post"), PUBLISHED, "abou", DateParts.NONE)))
                .isEmpty();
        assertThat(onSite(() -> lookups.firstBySlugPrefix(List.of(), PUBLISHED, "abou", DateParts.NONE)))
                .isEmpty();
        // the stored slugs are lower-case; the comparison is exact, and % and _ are no wildcards
        assertThat(prefix("ABOU", DateParts.NONE)).isEmpty();
        assertThat(prefix("%", DateParts.NONE)).isEmpty();
        assertThat(prefix("_", DateParts.NONE)).isEmpty();
        assertThat(prefix("5_", DateParts.NONE)).isEmpty();
    }

    @Test
    void oldSlugsAndOldDaysWhateverTheStatus() throws Exception {
        PostSnapshot hello = post("post", "publish", "hello", "2025-11-20 10:15:30");
        update(hello.id(), PostChanges.builder().slug("hello-world").build());
        PostSnapshot second = post("post", "publish", "second-post", "2025-11-01 08:00:00");
        update(
                second.id(),
                PostChanges.builder()
                        .date(LocalDateTime.parse("2025-11-23T08:00:00"))
                        .build());
        PostSnapshot draft = post("post", "publish", "first-draft", "2025-11-10 08:00:00");
        update(draft.id(), PostChanges.builder().slug("final-draft").build());
        update(draft.id(), PostChanges.builder().status("draft").build());

        assertThat(onSite(() -> lookups.firstByOldSlug("post", "hello", DateParts.NONE)))
                .contains(hello.id());
        assertThat(onSite(() -> lookups.firstByOldSlug("post", "hello", DateParts.of(2025, 11, 20))))
                .contains(hello.id());
        assertThat(onSite(() -> lookups.firstByOldSlug("post", "hello", DateParts.of(2025, 11, 1))))
                .isEmpty();
        assertThat(onSite(() -> lookups.firstByOldSlug("page", "hello", DateParts.NONE)))
                .isEmpty();
        // a draft's old slug is still found
        assertThat(onSite(() -> lookups.firstByOldSlug("post", "first-draft", DateParts.NONE)))
                .contains(draft.id());

        // the old day of a post with that slug now
        assertThat(onSite(() -> lookups.firstByOldDate("post", "second-post", DateParts.of(2025, 11, 1))))
                .contains(second.id());
        assertThat(onSite(() -> lookups.firstByOldDate("post", "second-post", DateParts.of(2025, 11, 2))))
                .isEmpty();
        assertThat(onSite(() -> lookups.firstByOldDate("post", "second-post", DateParts.NONE)))
                .isEmpty();
        // an old slug with an old day: post 1 never moved
        assertThat(onSite(() -> lookups.firstByOldSlugAndDate("post", "hello", DateParts.of(2025, 11, 1))))
                .isEmpty();
        PostSnapshot moved = post("post", "publish", "old-name", "2025-10-01 08:00:00");
        update(
                moved.id(),
                PostChanges.builder()
                        .slug("new-name")
                        .date(LocalDateTime.parse("2025-10-05T08:00:00"))
                        .build());
        assertThat(onSite(() -> lookups.firstByOldSlugAndDate("post", "old-name", DateParts.of(2025, 10, 1))))
                .contains(moved.id());
        assertThat(onSite(() -> lookups.firstByOldSlugAndDate("post", "old-name", DateParts.of(2025, 0, 0))))
                .contains(moved.id());
    }
}
