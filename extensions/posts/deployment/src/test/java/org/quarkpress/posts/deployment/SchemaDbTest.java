package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

class SchemaDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(SchemaDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Test
    void thePostsBandRunsAfterTheSecurityBand() {
        assertThat(Jdbc.strings(
                        dataSource,
                        "select script from qp_schema_history where success = true and type = 'SQL'"
                                + " order by installed_rank"))
                .containsSubsequence(
                        "db/quarkpress/" + TestDb.kind() + "/V3_001__security_create_tables.sql",
                        "db/quarkpress/" + TestDb.kind() + "/V4_001__posts_create_tables.sql");
    }

    @Test
    void aPostRowGetsItsColumnDefaults() {
        SiteId site = TestData.site(dataSource);
        PostId post = TestData.post(dataSource, site, "post", "draft", "", "Hello");
        assertThat(Jdbc.strings(
                        dataSource,
                        "select concat(slug, '|', menu_order, '|', password, '|', version) from qp_post where id = ?",
                        post.value()))
                .containsExactly("|0||0");
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_post where id = ? and sticky = false", post.value()))
                .isEqualTo(1);
        assertThat(Jdbc.strings(dataSource, "select meta from qp_post where id = ?", post.value()))
                .containsExactly("{}");
    }

    @Test
    void aParentMustBeAPostOfTheSameSite() {
        SiteId one = TestData.site(dataSource);
        SiteId two = TestData.site(dataSource);
        PostId parent = TestData.post(dataSource, one, "page", "publish", "parent", "Parent");
        PostId child = TestData.post(dataSource, two, "page", "publish", "child", "Child");
        assertThatThrownBy(() -> Jdbc.update(
                        dataSource, "update qp_post set parent_id = ? where id = ?", parent.value(), child.value()))
                .satisfies(failure -> assertThat(Failures.messages(failure)).contains("qp_post_parent_fk"));
        PostId sibling = TestData.post(dataSource, one, "page", "publish", "sibling", "Sibling");
        Jdbc.update(dataSource, "update qp_post set parent_id = ? where id = ?", parent.value(), sibling.value());
        assertThat(Jdbc.count(dataSource, "select parent_id from qp_post where id = ?", sibling.value()))
                .isEqualTo(parent.value());
    }

    @Test
    void deletingASiteRemovesItsPostsAndItsPostLock() {
        SiteId site = TestData.site(dataSource);
        PostId top = TestData.post(dataSource, site, "page", "publish", "top", "Top");
        PostId middle = TestData.post(dataSource, site, "page", "publish", "middle", "Middle");
        PostId bottom = TestData.post(dataSource, site, "page", "publish", "bottom", "Bottom");
        Jdbc.update(dataSource, "update qp_post set parent_id = ? where id = ?", top.value(), middle.value());
        Jdbc.update(dataSource, "update qp_post set parent_id = ? where id = ?", middle.value(), bottom.value());
        Jdbc.update(dataSource, "insert into qp_post_lock (site_id) values (?)", site.value());
        Jdbc.update(dataSource, "delete from qp_site where id = ?", site.value());
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_post where site_id = ?", site.value()))
                .isZero();
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_post_lock where site_id = ?", site.value()))
                .isZero();
    }

    @Test
    void aDeletedAuthorLeavesThePostWithoutAuthor() {
        SiteId site = TestData.site(dataSource);
        UserId author = TestData.profile(dataSource, "author");
        PostId post = TestData.post(dataSource, site, "post", "publish", "by-author", "By author");
        Jdbc.update(dataSource, "update qp_post set author_id = ? where id = ?", author.value(), post.value());
        Jdbc.update(dataSource, "delete from qp_user_profile where id = ?", author.value());
        assertThat(Jdbc.count(
                        dataSource, "select count(*) from qp_post where id = ? and author_id is null", post.value()))
                .isEqualTo(1);
    }

    @Test
    void metaKeepsKeyOrderAndMysqlRefusesInvalidJson() {
        SiteId site = TestData.site(dataSource);
        PostId post = TestData.post(dataSource, site, "post", "draft", "", "Meta");
        Jdbc.update(
                dataSource,
                "update qp_post set meta = " + TestDb.json() + " where id = ?",
                "{\"z\":[\"1\"],\"a\":[2]}",
                post.value());
        assertThat(Jdbc.strings(dataSource, "select meta from qp_post where id = ?", post.value()))
                .containsExactly("{\"z\":[\"1\"],\"a\":[2]}");
        if (TestDb.kind().equals("mysql")) {
            assertThatThrownBy(() ->
                            Jdbc.update(dataSource, "update qp_post set meta = 'not json' where id = ?", post.value()))
                    .satisfies(failure -> assertThat(Failures.messages(failure)).contains("qp_post_meta_json"));
        }
    }
}
