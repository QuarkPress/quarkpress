package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.UserId;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Upload;
import org.quarkpress.posts.MetaKeyDefinition;
import org.quarkpress.posts.MetaKeyProvider;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.testing.wprestparity.MediaFixtures;

/**
 * Updates of an attachment through {@code POST/PUT/PATCH /wp/v2/media/<id>}, as WordPress makes them: the update
 * permission (no upload capability needed, another author, stickiness); the parent ({@code post}: another post of the
 * site, 0 to detach, never an attachment or a revision, never a post the site does not have); the file, which an
 * update never changes; the statuses; the featured image, applied to audio and video only and never refused; the
 * modification date, which every update moves; a stored template accepted as it is; and one transaction, which a meta
 * refusal rolls back whole. Members of the main site: the editor bob, the author carol; alice is a super admin.
 */
class MediaUpdateDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(
                    MediaUpdateDbTest.class, PostFixtures.class, PostFixtures.Builder.class, AttachmentMeta.class)
            .overrideConfigKey("quarkus.http.body.uploads-directory", "target/uploads-update");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MEDIA = "/wp-json/wp/v2/media/";

    private static final String CANNOT_EDIT = "Sorry, you are not allowed to edit this post.";

    static AgroalDataSource data;
    static UserId alice;
    static UserId bob;
    static UserId carol;
    static UserId uploader;

    @BeforeAll
    static void members() {
        data = Jdbc.dataSource();
        alice = TestData.profile(data, "administrator");
        TestData.superAdmin(data, alice);
        bob = TestData.memberOfMain(data, "editor");
        carol = TestData.memberOfMain(data, "author");
        // a contributor who may upload: no publish_posts
        uploader = TestData.profile(data, "uploader");
        Jdbc.update(
                data,
                "insert into qp_site_membership (site_id, user_id, roles, capabilities) values (1, ?, " + TestDb.json()
                        + ", " + TestDb.json() + ")",
                uploader.value(),
                "[\"contributor\"]",
                "{\"upload_files\": true}");
    }

    /** An attachment meta key no one may write. */
    @ApplicationScoped
    public static class AttachmentMeta implements MetaKeyProvider {

        @Override
        public List<MetaKeyDefinition> metaKeys(PostTypes types) {
            return List.of(MetaKeyDefinition.builder("qp_locked")
                    .subtype("attachment")
                    .type("string")
                    .single()
                    .showInRest()
                    .auth((allowed, key, post, user, capability) -> false)
                    .build());
        }
    }

    // ---- tests

    @Test
    void permissionAndParentRules() {
        long bobs = upload(bob, "jpg").post().id().value();
        // another author's attachment needs edit_others_posts
        error(patch(carol, bobs, "{\"alt_text\":\"x\"}"), 403, "rest_cannot_edit", CANNOT_EDIT);
        error(patch(null, bobs, "{\"alt_text\":\"x\"}"), 401, "rest_cannot_edit", CANNOT_EDIT);
        JsonNode edited = ok(patch(bob, bobs, "{\"alt_text\":\"x\"}"));
        assertThat(edited.get("alt_text").asText()).isEqualTo("x");
        long carols = upload(carol, "jpg").post().id().value();
        error(
                patch(carol, carols, "{\"author\":" + bob.value() + "}"),
                403,
                "rest_cannot_edit_others",
                "Sorry, you are not allowed to update posts as this user.");
        error(
                patch(carol, carols, "{\"sticky\":true}"),
                403,
                "rest_cannot_assign_sticky",
                "Sorry, you are not allowed to make posts sticky.");
        // a missing id, a post that is no attachment
        PostId bobsDraft = draft(bob);
        error(patch(bob, 999_999_999L, "{}"), 404, "rest_post_invalid_id", "Invalid post ID.");
        error(patch(bob, bobsDraft.value(), "{}"), 404, "rest_post_invalid_id", "Invalid post ID.");
        // the new parent needs no permission: carol attaches her image to bob's draft
        JsonNode attached = ok(patch(carol, carols, "{\"post\":" + bobsDraft.value() + "}"));
        assertThat(attached.get("post").longValue()).isEqualTo(bobsDraft.value());
        assertThat(parentOf(carols)).isEqualTo(bobsDraft.value());
        // a post the site does not have is refused, nothing written
        error(
                patch(carol, carols, "{\"post\":999999999,\"title\":\"never\"}"),
                400,
                "rest_post_invalid_id",
                "Invalid post parent ID.");
        assertThat(parentOf(carols)).isEqualTo(bobsDraft.value());
        assertThat(titleOf(carols)).isNotEqualTo("never");
        // a negative id names no post either
        error(
                patch(carol, carols, "{\"post\":-5,\"title\":\"never\"}"),
                400,
                "rest_post_invalid_id",
                "Invalid post parent ID.");
        assertThat(parentOf(carols)).isEqualTo(bobsDraft.value());
        assertThat(titleOf(carols)).isNotEqualTo("never");
        // a revision, another attachment or the attachment itself are no parent
        PostId revision = PostFixtures.builder(TestData.MAIN)
                .type("revision")
                .status("inherit")
                .author(bob.value())
                .parent(bobsDraft)
                .title("revision " + suffix())
                .insert(data);
        String invalid =
                "{\"code\":\"rest_invalid_param\",\"message\":\"Invalid parent type.\",\"data\":{\"status\":400}}";
        for (long parent : List.of(revision.value(), bobs, carols)) {
            Response refused = patch(carol, carols, "{\"post\":" + parent + ",\"title\":\"never\"}");
            assertThat(refused.statusCode()).as(refused.asString()).isEqualTo(400);
            assertThat(refused.asString()).isEqualTo(invalid);
        }
        assertThat(titleOf(carols)).isNotEqualTo("never");
        // 0 detaches
        JsonNode detached = ok(patch(carol, carols, "{\"post\":0}"));
        assertThat(detached.get("post").isNull()).isTrue();
        assertThat(parentOf(carols)).isZero();
    }

    @Test
    void theAnswerIsTheEditContextWithTheItemsAllow() {
        long id = upload(carol, "jpg").post().id().value();
        Response answer = Http.as(bearer(carol))
                .contentType("application/json")
                .body("{\"title\":\"edited\"}")
                .put(MEDIA + id + "?context=view&_fields=id,title,alt_text");
        JsonNode body = ok(answer);
        assertThat(body.get("title").get("raw").asText()).isEqualTo("edited");
        assertThat(fieldNames(body)).containsExactly("id", "title", "alt_text");
        // the update is served; delete is not yet
        assertThat(answer.header("Allow")).isEqualTo("GET, POST, PUT, PATCH");
        assertThat(ok(Http.as(bearer(carol))
                                .contentType("application/json")
                                .body("{\"title\":\"posted\"}")
                                .post(MEDIA + id))
                        .at("/title/raw")
                        .asText())
                .isEqualTo("posted");
        // one who may not edit the attachment is offered its read alone
        long bobs = upload(bob, "jpg").post().id().value();
        Response read = Http.as(bearer(carol)).get(MEDIA + bobs);
        assertThat(read.statusCode()).as(read.asString()).isEqualTo(200);
        assertThat(read.header("Allow")).isEqualTo("GET");
        assertThat(texts(json(read.asString()).at("/_links/self/0/targetHints/allow")))
                .containsExactly("GET");
        assertThat(texts(
                        json(Http.as(bearer(bob)).get(MEDIA + bobs).asString()).at("/_links/self/0/targetHints/allow")))
                .containsExactly("GET", "POST", "PUT", "PATCH");
    }

    @Test
    void theFileNeverChanges() {
        MediaSnapshot stored = upload(carol, "jpg");
        long id = stored.post().id().value();
        JsonNode before = ok(Http.as(bearer(carol)).get(MEDIA + id + "?context=edit"));
        Response answer = Http.as(bearer(carol))
                .multiPart("file", "other.png", MediaFixtures.png(80, 60), "image/png")
                .multiPart("title", "replaced?")
                .post(MEDIA + id);
        JsonNode after = ok(answer);
        assertThat(after.get("source_url")).isEqualTo(before.get("source_url"));
        assertThat(after.get("mime_type").asText()).isEqualTo("image/jpeg");
        assertThat(after.get("media_details")).isEqualTo(before.get("media_details"));
        assertThat(after.at("/title/raw").asText()).isEqualTo("replaced?");
        assertThat(answer.header("X-WP-Upload-Attachment-ID")).isNull();
        // a raw body is ignored too: the query's fields apply
        JsonNode raw = ok(Http.as(bearer(carol))
                .contentType("image/png")
                .header("Content-Disposition", "attachment; filename=raw.png")
                .body(MediaFixtures.png(8, 8))
                .post(MEDIA + id + "?title=raw"));
        assertThat(raw.get("source_url")).isEqualTo(before.get("source_url"));
        assertThat(raw.get("media_details")).isEqualTo(before.get("media_details"));
        assertThat(raw.at("/title/raw").asText()).isEqualTo("raw");
    }

    @Test
    void statusesOnUpdate() {
        long privateOne = upload(carol, "jpg", "private").post().id().value();
        assertThat(statusOf(privateOne)).isEqualTo("private");
        // inherit is neither the stored status nor in the enum
        Response inherit = patch(carol, privateOne, "{\"status\":\"inherit\"}");
        error(inherit, 400, "rest_invalid_param", "Invalid parameter(s): status");
        assertThat(json(inherit.asString()).at("/data/details/status/code").asText())
                .isEqualTo("rest_not_in_enum");
        error(
                patch(carol, privateOne, "{\"status\":\"trash\"}"),
                400,
                "rest_invalid_param",
                "Invalid parameter(s): status");
        // the stored status is always accepted
        assertThat(ok(patch(carol, privateOne, "{\"status\":\"private\"}"))
                        .get("status")
                        .asText())
                .isEqualTo("private");
        // publish makes it inherit again
        assertThat(ok(patch(carol, privateOne, "{\"status\":\"publish\"}"))
                        .get("status")
                        .asText())
                .isEqualTo("inherit");
        assertThat(statusOf(privateOne)).isEqualTo("inherit");
        long inheritOne = upload(carol, "jpg").post().id().value();
        assertThat(ok(patch(carol, inheritOne, "{\"status\":\"inherit\"}"))
                        .get("status")
                        .asText())
                .isEqualTo("inherit");
        assertThat(ok(patch(carol, inheritOne, "{\"status\":\"draft\"}"))
                        .get("status")
                        .asText())
                .isEqualTo("inherit");
        assertThat(ok(patch(carol, inheritOne, "{\"status\":\"private\"}"))
                        .get("status")
                        .asText())
                .isEqualTo("private");
        assertThat(statusOf(inheritOne)).isEqualTo("private");
        // private needs publish_posts: a contributor who may upload is refused, nothing written
        long contributors = upload(uploader, "jpg").post().id().value();
        error(
                patch(uploader, contributors, "{\"status\":\"private\",\"title\":\"never\"}"),
                403,
                "rest_cannot_publish",
                "Sorry, you are not allowed to create private posts in this post type.");
        assertThat(statusOf(contributors)).isEqualTo("inherit");
        assertThat(titleOf(contributors)).isNotEqualTo("never");
        // publish needs it too; draft is free and leaves the attachment inherit
        error(
                patch(uploader, contributors, "{\"status\":\"publish\"}"),
                403,
                "rest_cannot_publish",
                "Sorry, you are not allowed to publish posts in this post type.");
        assertThat(ok(patch(uploader, contributors, "{\"status\":\"draft\"}"))
                        .get("status")
                        .asText())
                .isEqualTo("inherit");
    }

    @Test
    void featuredMediaOnUpdate() {
        long cover = upload(carol, "jpg").post().id().value();
        long image = upload(carol, "jpg").post().id().value();
        // an image ignores the value: no thumbnail is stored, no refusal
        JsonNode onImage = ok(patch(carol, image, "{\"featured_media\":" + cover + "}"));
        assertThat(onImage.get("featured_media").longValue()).isZero();
        assertThat(meta(image).has("_thumbnail_id")).isFalse();
        long audio = upload(carol, "mp3").post().id().value();
        JsonNode onAudio = ok(patch(carol, audio, "{\"featured_media\":" + cover + "}"));
        assertThat(onAudio.get("featured_media").longValue()).isEqualTo(cover);
        assertThat(texts(onAudio.get("class_list"))).contains("has-post-thumbnail");
        // an id that names no attachment is ignored
        JsonNode invalid = ok(patch(carol, audio, "{\"featured_media\":999999999}"));
        assertThat(invalid.get("featured_media").longValue()).isEqualTo(cover);
        JsonNode cleared = ok(patch(carol, audio, "{\"featured_media\":0}"));
        assertThat(cleared.get("featured_media").longValue()).isZero();
        assertThat(texts(cleared.get("class_list"))).doesNotContain("has-post-thumbnail");
        // a video takes it too
        long video = upload(carol, "mp4").post().id().value();
        JsonNode onVideo = ok(patch(carol, video, "{\"featured_media\":" + cover + "}"));
        assertThat(onVideo.get("featured_media").longValue()).isEqualTo(cover);
        assertThat(meta(video).get("_thumbnail_id").toString()).contains(Long.toString(cover));
    }

    @Test
    void anEmptyUpdateStillMovesTheModificationDate() {
        long id = upload(carol, "jpg").post().id().value();
        backdate(id);
        JsonNode updated = ok(patch(carol, id, "{}"));
        assertThat(updated.get("modified_gmt").asText()).isGreaterThan("2020-01-01T00:00:00");
        assertThat(modifiedOf(id)).doesNotStartWith("2020-01-01");
    }

    @Test
    void altTextOnlyUpdate() {
        long id = upload(carol, "jpg", b -> b.altText("first")).post().id().value();
        backdate(id);
        JsonNode cleared = ok(patch(carol, id, "{\"alt_text\":\"\"}"));
        assertThat(cleared.get("alt_text").asText()).isEmpty();
        assertThat(cleared.get("modified_gmt").asText()).isGreaterThan("2020-01-01T00:00:00");
        assertThat(Jdbc.strings(data, "select alt_text from qp_media where post_id = ?", id))
                .containsExactly("");
        // sanitized as a text field
        assertThat(ok(patch(carol, id, "{\"alt_text\":\" <b>bold</b>\\n alt \"}"))
                        .get("alt_text")
                        .asText())
                .isEqualTo("bold alt");
    }

    @Test
    void theOtherFieldsAreWritten() {
        long id = upload(carol, "jpg").post().id().value();
        JsonNode updated = ok(patch(
                carol,
                id,
                "{\"title\":{\"raw\":\"t\"},\"caption\":{\"raw\":\"c\"},\"description\":\"d\",\"slug\":\"Upd Slug\","
                        + "\"comment_status\":\"closed\",\"ping_status\":\"open\","
                        + "\"date\":\"2025-03-04T05:06:07\"}"));
        assertThat(updated.at("/title/raw").asText()).isEqualTo("t");
        assertThat(updated.at("/caption/raw").asText()).isEqualTo("c");
        assertThat(updated.at("/description/raw").asText()).isEqualTo("d");
        assertThat(updated.get("slug").asText()).isEqualTo("upd-slug");
        assertThat(updated.get("comment_status").asText()).isEqualTo("closed");
        assertThat(updated.get("ping_status").asText()).isEqualTo("open");
        assertThat(updated.get("date").asText()).isEqualTo("2025-03-04T05:06:07");
        // a date in the future never schedules an attachment
        JsonNode future = ok(patch(carol, id, "{\"date\":\"2099-01-01T00:00:00\"}"));
        assertThat(future.get("status").asText()).isEqualTo("inherit");
        // an editor gives it another author; an author that is no user is answered, nothing written
        assertThat(ok(patch(bob, id, "{\"author\":" + carol.value() + "}"))
                        .get("author")
                        .longValue())
                .isEqualTo(carol.value());
        error(
                patch(alice, id, "{\"author\":999999999,\"title\":\"never\"}"),
                400,
                "rest_invalid_author",
                "Invalid author ID.");
        assertThat(titleOf(id)).isEqualTo("t");
        // a cleared date dates the attachment now
        JsonNode cleared = ok(patch(carol, id, "{\"date\":null}"));
        assertThat(cleared.get("date_gmt").asText()).isGreaterThan("2026-01-01T00:00:00");
        assertThat(cleared.get("date_gmt").asText())
                .isNotEqualTo(future.get("date_gmt").asText());
        assertThat(cleared.get("status").asText()).isEqualTo("inherit");
    }

    @Test
    void aStoredTemplateIsAccepted() {
        long id = upload(carol, "jpg").post().id().value();
        ObjectNode meta = meta(id);
        meta.putArray("_wp_page_template").add("e07-template");
        Jdbc.update(data, "update qp_post set meta = " + TestDb.json() + " where id = ?", meta.toString(), id);
        JsonNode kept = ok(patch(carol, id, "{\"template\":\"e07-template\",\"title\":\"kept\"}"));
        assertThat(kept.get("template").asText()).isEqualTo("e07-template");
        Response other = patch(carol, id, "{\"template\":\"other\"}");
        error(other, 400, "rest_invalid_param", "Invalid parameter(s): template");
        // "" clears it
        assertThat(ok(patch(carol, id, "{\"template\":\"\"}")).get("template").asText())
                .isEmpty();
    }

    @Test
    void aMetaErrorRollsTheWholeUpdateBack() {
        long audio = upload(carol, "mp3").post().id().value();
        long cover = upload(carol, "jpg").post().id().value();
        Response refused = patch(
                carol,
                audio,
                "{\"title\":\"never\",\"alt_text\":\"never\",\"featured_media\":" + cover
                        + ",\"meta\":{\"qp_locked\":\"x\"}}");
        assertThat(refused.statusCode()).as(refused.asString()).isEqualTo(403);
        assertThat(json(refused.asString()).get("code").asText()).isEqualTo("rest_cannot_update");
        assertThat(titleOf(audio)).isNotEqualTo("never");
        assertThat(Jdbc.strings(data, "select alt_text from qp_media where post_id = ?", audio))
                .containsExactly("");
        assertThat(meta(audio).has("_thumbnail_id")).isFalse();
    }

    // ---- helpers

    private static MediaSnapshot upload(UserId user, String extension) {
        return upload(user, extension, b -> b);
    }

    private static MediaSnapshot upload(UserId user, String extension, String status) {
        return upload(user, extension, b -> b.status(status));
    }

    private static MediaSnapshot upload(UserId user, String extension, UnaryOperator<Upload.Builder> fields) {
        return MediaFixturesForRest.upload(TestData.MAIN, user, "u" + suffix() + "." + extension, false, fields);
    }

    private static Response patch(UserId user, long id, String body) {
        return Http.as(user == null ? null : bearer(user))
                .contentType("application/json")
                .body(body)
                .patch(MEDIA + id);
    }

    private static String bearer(UserId user) {
        return "test-" + user.value();
    }

    private static JsonNode ok(Response answer) {
        assertThat(answer.statusCode()).as(answer.asString()).isEqualTo(200);
        return json(answer.asString());
    }

    private static JsonNode error(Response answer, int status, String code, String message) {
        assertThat(answer.statusCode()).as(answer.asString()).isEqualTo(status);
        JsonNode body = json(answer.asString());
        assertThat(body.get("code").asText()).as(answer.asString()).isEqualTo(code);
        assertThat(body.get("message").asText()).isEqualTo(message);
        assertThat(body.at("/data/status").intValue()).isEqualTo(status);
        return body;
    }

    private static PostId draft(UserId author) {
        return PostFixtures.builder(TestData.MAIN)
                .type("post")
                .status("draft")
                .author(author.value())
                .title("draft " + suffix())
                .insert(data);
    }

    private static long parentOf(long id) {
        String parent = Jdbc.strings(data, "select parent_id from qp_post where id = ?", id)
                .get(0);
        return parent == null ? 0 : Long.parseLong(parent);
    }

    private static String titleOf(long id) {
        return Jdbc.strings(data, "select title from qp_post where id = ?", id).get(0);
    }

    private static String statusOf(long id) {
        return Jdbc.strings(data, "select status from qp_post where id = ?", id).get(0);
    }

    private static String modifiedOf(long id) {
        return Jdbc.strings(data, "select modified_at from qp_post where id = ?", id)
                .get(0);
    }

    private static ObjectNode meta(long id) {
        String meta =
                Jdbc.strings(data, "select meta from qp_post where id = ?", id).get(0);
        return meta == null ? MAPPER.createObjectNode() : (ObjectNode) json(meta);
    }

    /** Moves the attachment's modification date back to 2020. */
    private static void backdate(long id) {
        Jdbc.update(
                data,
                "update qp_post set modified_at = " + PostFixtures.instant() + " where id = ?",
                PostFixtures.instantText("2020-01-01T00:00:00Z"),
                id);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        array.forEach(item -> texts.add(item.asText()));
        return texts;
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
