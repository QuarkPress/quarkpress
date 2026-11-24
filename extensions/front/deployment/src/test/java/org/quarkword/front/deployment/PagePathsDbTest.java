package org.quarkword.front.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.PostId;
import org.quarkword.core.SiteId;
import org.quarkword.core.UserId;
import org.quarkword.front.runtime.PagePaths;
import org.quarkword.posts.PostSnapshot;

/** The page path lookup over stored pages and attachments: exact parent chains, case-folded, any status but trash. */
class PagePathsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = FrontApp.with(PagePathsDbTest.class);

    private static final List<String> PAGE_TYPES = List.of("page", "attachment");

    static SiteId site;
    static UserId alice;
    static PostId about;
    static PostId team;
    static PostId teamPhoto;
    static PostId trashedPage;
    static PostId draftPage;
    static PostId hello;
    static PostId numeric;

    @Inject
    @DataSource("quarkword")
    AgroalDataSource dataSource;

    @Inject
    PagePaths pages;

    @BeforeEach
    void cast() {
        if (site == null) {
            site = TestData.site(dataSource);
            alice = TestData.profile(dataSource, "alice", "alice");
            TestData.member(dataSource, TestData.MAIN, alice, "[\"author\"]");
            TestData.member(dataSource, site, alice, "[\"author\"]");
            about = post("page", "publish", "about", null);
            team = post("page", "publish", "team", about);
            teamPhoto = post("attachment", "inherit", "team-photo", team);
            trashedPage = post("page", "trash", "trashed-page", null);
            draftPage = post("page", "draft", "draft-page", null);
            hello = post("post", "publish", "hello-world", null);
            numeric = post("post", "publish", "2024", null);
            // a post with the page's slug is never a page path
            post("post", "publish", "team", null);
        }
    }

    private PostId post(String type, String status, String slug, PostId parent) {
        return TestData.post(dataSource, site, type, status, slug, alice, parent, "2025-11-20T10:00:00Z", false);
    }

    private Optional<PostId> find(String path, List<String> types) throws Exception {
        return Identities.onSite(
                site, Identities.anonymous(), () -> pages.find(path, types).map(PostSnapshot::id));
    }

    @Test
    void aPagePathIsItsParentsSlugsThenItsOwn() throws Exception {
        assertThat(find("about/team", PAGE_TYPES)).contains(team);
        assertThat(find("about", PAGE_TYPES)).contains(about);
        assertThat(find("/about/team/", PAGE_TYPES)).contains(team);
        assertThat(find("team", PAGE_TYPES)).isEmpty();
        assertThat(find("team/about", PAGE_TYPES)).isEmpty();
        assertThat(find("x/about/team", PAGE_TYPES)).isEmpty();
    }

    @Test
    void theLookupIgnoresCaseAndDecodesThePath() throws Exception {
        assertThat(find("About/Team", PAGE_TYPES)).contains(team);
        assertThat(find("about/Team%20", PAGE_TYPES)).contains(team);
        assertThat(find("about%2Fteam", PAGE_TYPES)).contains(team);
    }

    @Test
    void anAttachmentBelowAPageIsFoundByItsFullPath() throws Exception {
        assertThat(find("about/team/team-photo", PAGE_TYPES)).contains(teamPhoto);
        assertThat(find("team-photo", PAGE_TYPES)).isEmpty();
        assertThat(find("about/team/team-photo", List.of("page"))).isEmpty();
    }

    @Test
    void trashedPagesAreNotFoundButDraftsAre() throws Exception {
        assertThat(find("trashed-page", PAGE_TYPES)).isEmpty();
        assertThat(find("draft-page", PAGE_TYPES)).contains(draftPage);
    }

    @Test
    void onlyTheGivenTypesCount() throws Exception {
        assertThat(find("hello-world", PAGE_TYPES)).isEmpty();
        assertThat(find("hello-world", List.of("post", "attachment"))).contains(hello);
        assertThat(find("2024", List.of("post", "attachment"))).contains(numeric);
        assertThat(find("0", PAGE_TYPES)).isEmpty();
        assertThat(find("", PAGE_TYPES)).isEmpty();
    }
}
