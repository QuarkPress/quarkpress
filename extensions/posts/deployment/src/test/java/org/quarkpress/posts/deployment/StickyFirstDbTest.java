package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostPage;
import org.quarkpress.posts.PostQuery;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostType;
import org.quarkpress.posts.Posts;

/** WordPress's examples: posts 1 to 9 published with ascending dates, four per page. */
class StickyFirstDbTest {

    @PostType(key = "event", publicly = true, hasArchive = true)
    static class Event {}

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(StickyFirstDbTest.class, Event.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    SiteId site;
    UserId editor;
    UserId author;
    List<PostId> p;

    @BeforeEach
    void setUp() throws Exception {
        site = TestData.site(dataSource);
        editor = TestData.memberOfMain(dataSource, "editor");
        author = TestData.memberOfMain(dataSource, "author");
        p = new ArrayList<>();
        p.add(null);
        for (int i = 1; i <= 9; i++) {
            int day = i;
            PostChanges changes = PostChanges.builder()
                    .title("Post " + i)
                    .status("publish")
                    .author(i == 5 ? author : editor)
                    .dateGmt(Instant.parse("2020-01-01T00:00:00Z").plusSeconds(86_400L * day))
                    .build();
            p.add(Identities.onSite(site, Identities.system(), () -> posts.create(changes))
                    .id());
        }
    }

    private void stick(int... indexes) throws Exception {
        for (int index : indexes) {
            Identities.onSite(site, Identities.system(), () -> posts.stick(p.get(index)));
        }
    }

    private PostPage page(PostQuery.Builder query) throws Exception {
        return page(Identities.anonymous(), query.perPage(4));
    }

    private PostPage page(SecurityIdentity identity, PostQuery.Builder query) throws Exception {
        return Identities.onSite(
                site, identity, () -> posts.query(query.stickyFirst(true).build()));
    }

    private List<PostId> query(PostQuery.Builder query) throws Exception {
        return page(query).posts().stream().map(PostSnapshot::id).toList();
    }

    private List<PostId> ids(int... indexes) {
        List<PostId> ids = new ArrayList<>();
        for (int index : indexes) {
            ids.add(p.get(index));
        }
        return ids;
    }

    @Test
    void stickyPostsNotFetchedAreAddedByDate() throws Exception {
        stick(5, 3);
        assertThat(query(PostQuery.builder())).containsExactlyElementsOf(ids(5, 3, 9, 8, 7, 6));
        PostPage page = page(PostQuery.builder());
        assertThat(page.found()).isEqualTo(9);
        assertThat(page.maxPages()).isEqualTo(3);
    }

    @Test
    void fetchedStickyPostsMoveFirstInFetchOrder() throws Exception {
        stick(3, 8);
        assertThat(query(PostQuery.builder())).containsExactlyElementsOf(ids(8, 3, 9, 7, 6));
    }

    @Test
    void laterPagesAreLeftAlone() throws Exception {
        stick(5, 3);
        assertThat(query(PostQuery.builder().page(2))).containsExactlyElementsOf(ids(5, 4, 3, 2));
        assertThat(query(PostQuery.builder().page(-2))).containsExactlyElementsOf(ids(5, 4, 3, 2));
        assertThat(query(PostQuery.builder().page(0))).containsExactlyElementsOf(ids(5, 3, 9, 8, 7, 6));
    }

    @Test
    void anOffsetStaysOnPageOneAndUnpagedQueriesAlwaysAre() throws Exception {
        stick(8, 3);
        assertThat(query(PostQuery.builder().offset(4))).containsExactlyElementsOf(ids(3, 8, 5, 4, 2));
        List<PostId> unpaged = page(
                        Identities.anonymous(), PostQuery.builder().perPage(-1).page(3))
                .posts()
                .stream()
                .map(PostSnapshot::id)
                .toList();
        assertThat(unpaged).containsExactlyElementsOf(ids(8, 3, 9, 7, 6, 5, 4, 2, 1));
    }

    @Test
    void anEmptyFirstPageStillGetsTheStickyPosts() throws Exception {
        stick(5, 3);
        PostPage page = page(PostQuery.builder().title("No such post"));
        assertThat(page.posts()).extracting(PostSnapshot::id).containsExactlyElementsOf(ids(5, 3));
        assertThat(page.found()).isZero();
    }

    @Test
    void anAuthorArchiveHasNoStickyPostsButAuthorInHas() throws Exception {
        stick(5, 3);
        assertThat(query(PostQuery.builder().author(editor))).containsExactlyElementsOf(ids(9, 8, 7, 6));
        assertThat(query(PostQuery.builder().authorIn(editor))).containsExactlyElementsOf(ids(5, 3, 9, 8, 7, 6));
    }

    @Test
    void postInDoesNotKeepStickyPostsOut() throws Exception {
        stick(5, 3);
        assertThat(query(PostQuery.builder().postIn(p.get(1), p.get(2)))).containsExactlyElementsOf(ids(5, 3, 2, 1));
        assertThat(query(PostQuery.builder().postNotIn(p.get(5)))).containsExactlyElementsOf(ids(3, 9, 8, 7, 6));
    }

    @Test
    void stickyPostsOfOtherTypesAreNotAdded() throws Exception {
        stick(3);
        PostId page = Identities.onSite(
                        site,
                        Identities.system(),
                        () -> posts.create(PostChanges.builder()
                                .type("page")
                                .title("Page")
                                .status("publish")
                                .build()))
                .id();
        assertThat(query(PostQuery.builder().types("page"))).containsExactly(page);
    }

    @Test
    void onlyPublishedStickyPostsOfTheQueriedTypesAreAdded() throws Exception {
        stick(3);
        Identities.onSite(
                site,
                Identities.system(),
                () -> posts.update(
                        p.get(3), PostChanges.builder().status("draft").build()));
        assertThat(query(PostQuery.builder())).containsExactlyElementsOf(ids(9, 8, 7, 6));
        assertThat(query(PostQuery.builder().types("page"))).isEmpty();
        List<PostId> withDrafts =
                page(
                                Identities.system(),
                                PostQuery.builder().statuses("publish", "draft").perPage(-1))
                        .posts()
                        .stream()
                        .map(PostSnapshot::id)
                        .toList();
        assertThat(withDrafts).containsExactlyElementsOf(ids(3, 9, 8, 7, 6, 5, 4, 2, 1));
    }

    @Test
    void aQueryForOneTypeWithAnArchiveHasNoStickyPosts() throws Exception {
        stick(3);
        PostId event = Identities.onSite(
                        site,
                        Identities.system(),
                        () -> posts.create(PostChanges.builder()
                                .type("event")
                                .title("Event")
                                .status("publish")
                                .dateGmt(Instant.parse("2020-02-01T00:00:00Z"))
                                .build()))
                .id();
        assertThat(query(PostQuery.builder().types("event"))).containsExactly(event);
        List<PostId> both = query(PostQuery.builder().types("event", "post"));
        List<PostId> expected = new ArrayList<>(ids(3));
        expected.add(event);
        expected.addAll(ids(9, 8, 7));
        assertThat(both).containsExactlyElementsOf(expected);
    }

    @Test
    void withoutStickyFirstOrWhenSearchingNothingMoves() throws Exception {
        stick(5);
        List<PostId> plain = Identities.onSite(
                        site,
                        Identities.anonymous(),
                        () -> posts.query(PostQuery.builder().perPage(4).build()))
                .posts()
                .stream()
                .map(PostSnapshot::id)
                .toList();
        assertThat(plain).containsExactlyElementsOf(ids(9, 8, 7, 6));
        assertThat(query(PostQuery.builder().search("post"))).containsExactlyElementsOf(ids(9, 8, 7, 6));
        assertThat(query(PostQuery.builder().search(""))).containsExactlyElementsOf(ids(9, 8, 7, 6));
    }

    @Test
    void aQueryForOnePostHasNoStickyPosts() throws Exception {
        stick(5);
        assertThat(query(PostQuery.builder().id(p.get(2)))).containsExactlyElementsOf(ids(2));
    }
}
