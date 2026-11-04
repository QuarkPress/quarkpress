package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

/**
 * {@code DELETE /wp/v2/<base>/<id>} with the trash turned off ({@code quarkpress.posts.empty-trash-days=0}): a delete
 * without {@code force} is refused with 501, before the already-trashed check, and changes nothing; {@code force}
 * still deletes.
 */
class PostDeleteNoTrashDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(
                    PostDeleteNoTrashDbTest.class, PostFixtures.class, PostFixtures.Builder.class)
            .overrideConfigKey("quarkpress.posts.empty-trash-days", "0");

    static AgroalDataSource data;
    static SiteId site;
    static String host;
    static long editor;

    @BeforeAll
    static void members() {
        data = Jdbc.dataSource();
        site = TestData.site(data);
        host = Jdbc.strings(data, "select domain from qp_site where id = ?", site.value())
                .get(0);
        UserId user = TestData.profile(data, "editor");
        TestData.member(data, site, user, "[\"editor\"]");
        editor = user.value();
    }

    static RequestSpecification as(long user) {
        return Http.at(host, "test-" + user);
    }

    private static String status(PostId post) {
        return Jdbc.strings(data, "select status from qp_post where id = ?", post.value())
                .get(0);
    }

    @Test
    void noTrashMeans501() {
        PostId live = PostFixtures.builder(site)
                .status("publish")
                .author(editor)
                .title("live")
                .insert(data);
        PostId trashed = PostFixtures.builder(site)
                .status("trash")
                .author(editor)
                .title("trashed")
                .insert(data);
        for (PostId post : new PostId[] {live, trashed}) {
            for (String query : new String[] {"", "?force=false"}) {
                as(editor)
                        .delete("/wp-json/wp/v2/posts/" + post.value() + query)
                        .then()
                        .statusCode(501)
                        .header("Allow", "GET, POST, PUT, PATCH, DELETE")
                        .body("code", equalTo("rest_trash_not_supported"))
                        .body("message", equalTo("The post does not support trashing. Set 'force=true' to delete."))
                        .body("data.status", equalTo(501));
            }
        }
        assertThat(status(live)).isEqualTo("publish");
        assertThat(status(trashed)).isEqualTo("trash");
        as(editor)
                .delete("/wp-json/wp/v2/posts/" + live.value() + "?force=true")
                .then()
                .statusCode(200)
                .body("deleted", equalTo(true))
                .body("previous.id", equalTo((int) live.value()));
        assertThat(Jdbc.count(data, "select count(*) from qp_post where id = ?", live.value()))
                .isZero();
    }
}
