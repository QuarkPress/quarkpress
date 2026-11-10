package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.testing.wprestparity.MediaFixtures;

/**
 * Deletes of an attachment through {@code DELETE /wp/v2/media/<id>}, as WordPress makes them with media trash off (its
 * default): the delete permission; without {@code force}, 501 once the permission passed, the attachment kept; with
 * {@code force}, the attachment as it was in the edit context without links, then the attachment and every file of it
 * gone. Members of the main site: the editor bob, the author carol.
 */
class MediaDeleteDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(
                    MediaDeleteDbTest.class, PostFixtures.class, PostFixtures.Builder.class)
            .overrideConfigKey("quarkus.http.body.uploads-directory", "target/uploads-delete");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MEDIA = "/wp-json/wp/v2/media";

    private static final String NO_TRASH = "{\"code\":\"rest_trash_not_supported\",\"message\":\"The post does not"
            + " support trashing. Set 'force=true' to delete.\",\"data\":{\"status\":501}}";

    static AgroalDataSource data;
    static UserId bob;
    static UserId carol;

    @BeforeAll
    static void members() {
        data = Jdbc.dataSource();
        bob = TestData.memberOfMain(data, "editor");
        carol = TestData.memberOfMain(data, "author");
    }

    // ---- tests

    @Test
    void withoutForceIs501AfterThePermission() {
        long carols = MediaFixturesForRest.upload(TestData.MAIN, carol, "d" + suffix() + ".jpg", false)
                .post()
                .id()
                .value();
        long bobs = MediaFixturesForRest.upload(TestData.MAIN, bob, "d" + suffix() + ".jpg", false)
                .post()
                .id()
                .value();
        // no force, or force false: media trash never exists
        Response refused = delete(carol, MEDIA + "/" + carols);
        assertThat(refused.statusCode()).as(refused.asString()).isEqualTo(501);
        assertThat(refused.asString()).isEqualTo(NO_TRASH);
        assertThat(refused.header("Allow")).isEqualTo("GET, POST, PUT, PATCH, DELETE");
        Response falseForce = delete(carol, MEDIA + "/" + carols + "?force=false");
        assertThat(falseForce.statusCode()).isEqualTo(501);
        assertThat(falseForce.asString()).isEqualTo(NO_TRASH);
        assertThat(exists(carols)).isTrue();
        // the permission comes first: another author's attachment, an anonymous caller
        error(
                delete(carol, MEDIA + "/" + bobs),
                403,
                "rest_cannot_delete",
                "Sorry, you are not allowed to delete this post.");
        assertThat(delete(carol, MEDIA + "/" + bobs).header("Allow")).isEqualTo("GET");
        error(
                delete(null, MEDIA + "/" + carols + "?force=true"),
                401,
                "rest_cannot_delete",
                "Sorry, you are not allowed to delete this post.");
        // a post that is no attachment, an id the site does not have
        PostId post = TestData.post(data, TestData.MAIN, carol, "post", "draft");
        error(delete(carol, MEDIA + "/" + post.value()), 404, "rest_post_invalid_id", "Invalid post ID.");
        error(
                delete(carol, MEDIA + "/" + post.value() + "?force=true"),
                404,
                "rest_post_invalid_id",
                "Invalid post ID.");
        assertThat(exists(post.value())).isTrue();
        error(delete(carol, MEDIA + "/999999999?force=true"), 404, "rest_post_invalid_id", "Invalid post ID.");
        assertThat(exists(bobs)).isTrue();
    }

    @Test
    void forceDeletesAndAnswersThePrevious() {
        Response uploaded = Http.as(bearer(carol))
                .multiPart("file", "big" + suffix() + ".jpg", MediaFixtures.jpeg(1200, 800), "image/jpeg")
                .multiPart("title", "to be deleted")
                .post(MEDIA);
        assertThat(uploaded.statusCode()).as(uploaded.asString()).isEqualTo(201);
        JsonNode attachment = json(uploaded.asString());
        long id = attachment.get("id").longValue();
        List<String> files = new ArrayList<>();
        files.add(path(attachment.get("source_url").asText()));
        attachment.at("/media_details/sizes").forEach(size -> {
            if (!size.get("file")
                    .asText()
                    .equals(attachment.at("/media_details/file").asText())) {
                files.add(path(size.get("source_url").asText()));
            }
        });
        assertThat(files).hasSizeGreaterThan(3);
        for (String file : files) {
            assertThat(Http.as(null).get(file).statusCode()).as(file).isEqualTo(200);
        }

        // the view context is asked for; the previous attachment is the edit context's, without links
        Response deleted = Http.as(bearer(carol))
                .contentType("application/json")
                .body("{\"force\":true}")
                .delete(MEDIA + "/" + id + "?context=view");
        assertThat(deleted.statusCode()).as(deleted.asString()).isEqualTo(200);
        JsonNode body = json(deleted.asString());
        assertThat(fieldNames(body)).containsExactly("deleted", "previous");
        assertThat(body.get("deleted").booleanValue()).isTrue();
        JsonNode previous = body.get("previous");
        assertThat(previous.get("id").longValue()).isEqualTo(id);
        assertThat(previous.has("_links")).isFalse();
        assertThat(previous.at("/title/raw").asText()).isEqualTo("to be deleted");
        assertThat(previous.has("permalink_template")).isTrue();
        assertThat(previous.at("/media_details/sizes")).isEqualTo(attachment.at("/media_details/sizes"));
        // the attachment is gone, and every file of it
        assertThat(deleted.header("Allow")).isNull();
        assertThat(exists(id)).isFalse();
        error(Http.as(bearer(carol)).get(MEDIA + "/" + id), 404, "rest_post_invalid_id", "Invalid post ID.");
        for (String file : files) {
            assertThat(Http.as(null).get(file).statusCode()).as(file).isEqualTo(404);
        }
    }

    @Test
    void anotherSitesAttachmentIs404AndKept() {
        SiteId other = TestData.site(data);
        UserId editor = TestData.profile(data, "editor");
        TestData.member(data, other, editor, "[\"editor\"]");
        TestData.member(data, TestData.MAIN, editor, "[\"editor\"]");
        MediaSnapshot theirs = MediaFixturesForRest.upload(other, editor, "o" + suffix() + ".jpg", false);
        long id = theirs.post().id().value();
        String host = Jdbc.strings(data, "select domain from qp_site where id = ?", other.value())
                .get(0);
        String file = path(theirs.url());
        assertThat(Http.at(host, null).get(file).statusCode()).isEqualTo(200);
        // asked on the main site, the id names no attachment of it
        error(delete(editor, MEDIA + "/" + id + "?force=true"), 404, "rest_post_invalid_id", "Invalid post ID.");
        assertThat(exists(id)).isTrue();
        assertThat(Http.at(host, null).get(file).statusCode()).isEqualTo(200);
    }

    @Test
    void anAttachmentTheMediaModuleCannotDeleteIs500() {
        // an attachment post written through the posts module alone: no media row, nothing for the media delete
        PostId fileless = PostFixtures.builder(TestData.MAIN)
                .type("attachment")
                .status("inherit")
                .author(carol.value())
                .title("fileless " + suffix())
                .insert(data);
        error(
                delete(carol, MEDIA + "/" + fileless.value() + "?force=1"),
                500,
                "rest_cannot_delete",
                "The post cannot be deleted.");
    }

    // ---- helpers

    private static Response delete(UserId user, String target) {
        return Http.as(user == null ? null : bearer(user)).delete(target);
    }

    private static String bearer(UserId user) {
        return "test-" + user.value();
    }

    private static boolean exists(long id) {
        return !Jdbc.strings(data, "select id from qp_post where id = ?", id).isEmpty();
    }

    /** The path of a file URL, which the test application serves whatever its host and port. */
    private static String path(String url) {
        return URI.create(url).getRawPath();
    }

    private static JsonNode error(Response answer, int status, String code, String message) {
        assertThat(answer.statusCode()).as(answer.asString()).isEqualTo(status);
        JsonNode body = json(answer.asString());
        assertThat(body.get("code").asText()).as(answer.asString()).isEqualTo(code);
        assertThat(body.get("message").asText()).isEqualTo(message);
        assertThat(body.at("/data/status").intValue()).isEqualTo(status);
        return body;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
