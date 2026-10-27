package org.quarkpress.revisions.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.ForbiddenException;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.revisions.Revisions;

/** The number of a site's revisions, autosaves included, for those who may edit others' posts. */
class CountDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RevisionsApp.with(CountDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Revisions revisions;

    @Test
    void countAllCountsEveryRevisionAndAutosaveOfTheCurrentSiteOnly() throws Exception {
        SiteId other = TestData.site(dataSource);
        UserId author = TestData.profile(dataSource, "author");
        PostId a = TestData.post(dataSource, TestData.MAIN, "post", "publish", "a", "A");
        PostId b = TestData.post(dataSource, other, "post", "publish", "b", "B");
        TestData.revision(dataSource, TestData.MAIN, a, null, "A1", "", "", "{}", false, "2026-01-15T12:00:00Z");
        TestData.revision(dataSource, TestData.MAIN, a, null, "A2", "", "", "{}", false, "2026-01-15T12:01:00Z");
        TestData.revision(dataSource, TestData.MAIN, a, author, "A3", "", "", "{}", true, "2026-01-15T12:02:00Z");
        TestData.revision(dataSource, other, b, null, "B1", "", "", "{}", false, "2026-01-15T12:00:00Z");
        assertThat(Identities.onSite(TestData.MAIN, Identities.system(), revisions::countAll))
                .isEqualTo(3);
        assertThat(Identities.onSite(other, Identities.system(), revisions::countAll))
                .isEqualTo(1);
    }

    @Test
    void countAllNeedsEditOthersPosts() {
        UserId author = TestData.profile(dataSource, "author");
        TestData.member(dataSource, TestData.MAIN, author, "[\"author\"]");
        assertThatThrownBy(() -> Identities.onSite(TestData.MAIN, Identities.user(author), revisions::countAll))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("the caller lacks the capability edit_others_posts on site 1");
    }
}
