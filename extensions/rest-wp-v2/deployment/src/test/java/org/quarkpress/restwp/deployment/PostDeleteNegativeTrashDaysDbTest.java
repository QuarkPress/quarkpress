package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

/**
 * {@code DELETE /wp/v2/<base>/<id>} with a negative {@code quarkpress.posts.empty-trash-days}: the trash is supported
 * only for a positive number of days, as WordPress decides, so a delete without {@code force} is refused with 501 and
 * changes nothing.
 */
class PostDeleteNegativeTrashDaysDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(
                    PostDeleteNegativeTrashDaysDbTest.class, PostFixtures.class, PostFixtures.Builder.class)
            .overrideConfigKey("quarkpress.posts.empty-trash-days", "-1");

    @Test
    void negativeDaysMean501() {
        AgroalDataSource data = Jdbc.dataSource();
        SiteId site = TestData.site(data);
        String host = Jdbc.strings(data, "select domain from qp_site where id = ?", site.value())
                .get(0);
        UserId editor = TestData.profile(data, "editor");
        TestData.member(data, site, editor, "[\"editor\"]");
        PostId post = PostFixtures.builder(site)
                .status("publish")
                .author(editor.value())
                .title("live")
                .insert(data);
        Http.at(host, "test-" + editor.value())
                .delete("/wp-json/wp/v2/posts/" + post.value())
                .then()
                .statusCode(501)
                .body("code", equalTo("rest_trash_not_supported"))
                .body("message", equalTo("The post does not support trashing. Set 'force=true' to delete."));
        assertThat(Jdbc.strings(data, "select status from qp_post where id = ?", post.value()))
                .containsExactly("publish");
    }
}
