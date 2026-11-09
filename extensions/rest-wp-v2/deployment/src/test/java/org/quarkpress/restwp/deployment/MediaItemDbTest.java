package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import java.io.IOException;
import java.io.UncheckedIOException;
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

/**
 * A single attachment ({@code GET /wp/v2/media/<id>}): the lookup and the checks in WordPress's order, the fields of
 * the view, edit and embed contexts with their values, the file URLs (switched to {@code https} for a secure request,
 * the stored guid kept), the attachment fields a nested {@code _fields} never computes, and the alternate
 * {@code Link}. The editor bob uploads on the main site; the author carol and the subscriber erin read.
 */
class MediaItemDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(
                    MediaItemDbTest.class, PostFixtures.class, PostFixtures.Builder.class)
            .overrideConfigKey("quarkus.http.proxy.proxy-address-forwarding", "true");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String UPLOADS = "http://localhost/wp-content/uploads/";

    static AgroalDataSource data;
    static UserId bob;
    static UserId carol;
    static UserId erin;

    @BeforeAll
    static void members() {
        data = Jdbc.dataSource();
        bob = TestData.memberOfMain(data, "editor");
        carol = TestData.memberOfMain(data, "author");
        erin = TestData.memberOfMain(data, "subscriber");
    }

    private static MediaSnapshot upload(String name) {
        return MediaFixturesForRest.upload(TestData.MAIN, bob, name, false);
    }

    private static String bearer(UserId user) {
        return "test-" + user.value();
    }

    private static JsonNode get(UserId user, String target) {
        return json(Http.as(user == null ? null : bearer(user))
                .get("/wp-json/wp/v2/media/" + target)
                .then()
                .statusCode(200)
                .extract()
                .asString());
    }

    @Test
    void viewEditAndEmbedContextsHaveWordPresssKeys() {
        String u = UUID.randomUUID().toString().substring(0, 8);
        MediaSnapshot jpeg = upload("k" + u + ".jpg");
        MediaSnapshot pdf = upload("k" + u + ".pdf");
        long id = jpeg.post().id().value();
        List<String> view = List.of(
                "id",
                "date",
                "date_gmt",
                "guid",
                "modified",
                "modified_gmt",
                "slug",
                "status",
                "type",
                "link",
                "title",
                "author",
                "featured_media",
                "comment_status",
                "ping_status",
                "template",
                "meta",
                "class_list",
                "description",
                "caption",
                "alt_text",
                "media_type",
                "mime_type",
                "media_details",
                "post",
                "source_url",
                "filename",
                "filesize");
        JsonNode viewed = get(null, Long.toString(id));
        assertThat(fieldNames(viewed)).containsExactlyElementsOf(withLinks(view));
        assertThat(fieldNames(viewed.get("guid"))).containsExactly("rendered");
        assertThat(fieldNames(viewed.get("title"))).containsExactly("rendered");
        assertThat(fieldNames(viewed.get("description"))).containsExactly("rendered");
        assertThat(fieldNames(viewed.get("caption"))).containsExactly("rendered");
        String url = jpeg.url();
        assertThat(url).startsWith(UPLOADS);
        assertThat(viewed.get("source_url").asText()).isEqualTo(url);
        assertThat(viewed.at("/guid/rendered").asText()).isEqualTo(jpeg.post().guid());
        assertThat(viewed.get("mime_type").asText()).isEqualTo("image/jpeg");
        assertThat(viewed.get("filename").asText()).isEqualTo("k" + u + ".jpg");
        assertThat(viewed.get("filesize").asLong()).isEqualTo(jpeg.fileSize()).isPositive();
        assertThat(viewed.get("media_details").toString())
                .startsWith("{\"width\":64,\"height\":48,\"file\":\"" + jpeg.attachedFile() + "\",\"filesize\":"
                        + jpeg.fileSize() + ",\"sizes\":{},\"image_meta\":{");
        assertThat(viewed.at("/description/rendered").asText())
                .isEqualTo("<p class=\"attachment\"><a href='" + url + "'><img loading=\"lazy\" decoding=\"async\""
                        + " width=\"64\" height=\"48\" src=\"" + url + "\" class=\"attachment-medium size-medium\""
                        + " alt=\"\" /></a></p>\n");
        assertThat(viewed.at("/caption/rendered").asText()).isEmpty();

        List<String> edit = new ArrayList<>(view);
        edit.addAll(edit.indexOf("meta") + 1, List.of("permalink_template", "generated_slug"));
        edit.add(edit.indexOf("source_url") + 1, "missing_image_sizes");
        List<String> image = new ArrayList<>(edit);
        image.addAll(List.of("exif_orientation", "image_output_format", "image_save_progressive", "image_quality"));
        JsonNode edited = get(bob, id + "?context=edit");
        assertThat(fieldNames(edited)).containsExactlyElementsOf(withLinks(image));
        assertThat(fieldNames(edited.get("guid"))).containsExactly("rendered", "raw");
        assertThat(fieldNames(edited.get("description"))).containsExactly("raw", "rendered");
        assertThat(fieldNames(edited.get("caption"))).containsExactly("raw", "rendered");
        assertThat(edited.get("permalink_template").asText()).isEqualTo("http://localhost/?attachment_id=" + id);
        assertThat(edited.get("missing_image_sizes").toString()).isEqualTo("[]");
        assertThat(edited.get("exif_orientation").asInt()).isEqualTo(1);
        assertThat(edited.get("image_output_format").isNull()).isTrue();
        assertThat(edited.get("image_save_progressive").toString()).isEqualTo("false");
        assertThat(edited.get("image_quality").toString()).isEqualTo("{\"default\":82,\"sizes\":[]}");
        JsonNode editedPdf = get(bob, pdf.post().id().value() + "?context=edit");
        assertThat(fieldNames(editedPdf)).containsExactlyElementsOf(withLinks(edit));

        assertThat(fieldNames(get(null, id + "?context=embed")))
                .containsExactly(
                        "id",
                        "date",
                        "slug",
                        "type",
                        "link",
                        "title",
                        "author",
                        "featured_media",
                        "caption",
                        "alt_text",
                        "media_type",
                        "mime_type",
                        "media_details",
                        "source_url",
                        "_links");
    }

    /** {@code fields} followed by the links, which every answer that does not narrow its fields ends with. */
    private static List<String> withLinks(List<String> fields) {
        List<String> all = new ArrayList<>(fields);
        all.add("_links");
        return all;
    }

    @Test
    void unattachedIsNullAndMetaIsAnEmptyList() {
        MediaSnapshot png = upload("u" + UUID.randomUUID().toString().substring(0, 8) + ".png");
        long id = png.post().id().value();
        JsonNode item = get(erin, Long.toString(id));
        assertThat(item.get("post").isNull()).isTrue();
        assertThat(item.get("meta").toString()).isEqualTo("[]");
        assertThat(item.get("class_list").toString())
                .isEqualTo("[\"post-" + id + "\",\"attachment\",\"type-attachment\",\"status-inherit\",\"hentry\"]");
        assertThat(item.get("status").asText()).isEqualTo("inherit");
        assertThat(item.get("type").asText()).isEqualTo("attachment");
        assertThat(item.get("media_type").asText()).isEqualTo("image");
        assertThat(item.get("link").asText()).isEqualTo("http://localhost/?attachment_id=" + id);
        assertThat(item.get("author").asLong()).isEqualTo(bob.value());
        assertThat(item.get("featured_media").asLong()).isZero();
        assertThat(item.get("alt_text").asText()).isEmpty();
        // attached: the parent's id
        PostId parent = PostFixtures.builder(TestData.MAIN)
                .status("publish")
                .author(bob.value())
                .title("parent")
                .publishedAt("2026-03-01T10:00:00Z")
                .modifiedAt("2026-03-01T10:00:00Z")
                .insert(data);
        MediaSnapshot attached = MediaFixturesForRest.upload(
                TestData.MAIN,
                bob,
                "a" + UUID.randomUUID().toString().substring(0, 8) + ".jpg",
                false,
                upload -> upload.parent(parent));
        assertThat(get(null, Long.toString(attached.post().id().value()))
                        .get("post")
                        .asLong())
                .isEqualTo(parent.value());
    }

    @Test
    void theItemChecksComeInWordPresssOrder() {
        String u = UUID.randomUUID().toString().substring(0, 8);
        PostId aPost = PostFixtures.builder(TestData.MAIN)
                .status("publish")
                .author(bob.value())
                .title("a post " + u)
                .publishedAt("2026-03-01T10:00:00Z")
                .modifiedAt("2026-03-01T10:00:00Z")
                .insert(data);
        Http.as(bearer(bob))
                .get("/wp-json/wp/v2/media/" + aPost.value())
                .then()
                .statusCode(404)
                .body("code", equalTo("rest_post_invalid_id"))
                .body("message", equalTo("Invalid post ID."));
        Http.as(null)
                .get("/wp-json/wp/v2/media/999999999")
                .then()
                .statusCode(404)
                .body("code", equalTo("rest_post_invalid_id"));
        long bobs = upload("c" + u + ".jpg").post().id().value();
        // the edit context comes before the password, the password before the read decision
        Http.as(bearer(carol))
                .get("/wp-json/wp/v2/media/" + bobs + "?context=edit&password=x")
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_forbidden_context"))
                .body("message", equalTo("Sorry, you are not allowed to edit this post."));
        Http.as(null)
                .get("/wp-json/wp/v2/media/" + bobs + "?context=edit")
                .then()
                .statusCode(401)
                .body("code", equalTo("rest_forbidden_context"));
        Http.as(bearer(bob))
                .get("/wp-json/wp/v2/media/" + bobs + "?password=x")
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_post_incorrect_password"))
                .body("message", equalTo("Incorrect post password."));
        // a password given in the body only is no query password
        Http.as(bearer(bob))
                .contentType("application/json")
                .body("{\"password\":\"x\"}")
                .get("/wp-json/wp/v2/media/" + bobs)
                .then()
                .statusCode(200);
        PostId draft = PostFixtures.builder(TestData.MAIN)
                .status("draft")
                .author(bob.value())
                .title("draft " + u)
                .modifiedAt("2026-03-01T10:00:00Z")
                .insert(data);
        long hidden = MediaFixturesForRest.upload(
                        TestData.MAIN, bob, "d" + u + ".jpg", false, upload -> upload.parent(draft))
                .post()
                .id()
                .value();
        Http.as(null)
                .get("/wp-json/wp/v2/media/" + hidden + "?password=x")
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_post_incorrect_password"));
        Http.as(null)
                .get("/wp-json/wp/v2/media/" + hidden)
                .then()
                .statusCode(401)
                .body("code", equalTo("rest_forbidden"))
                .body("message", equalTo("Sorry, you are not allowed to do that."));
        for (UserId reader : List.of(erin, carol)) {
            Http.as(bearer(reader))
                    .get("/wp-json/wp/v2/media/" + hidden)
                    .then()
                    .statusCode(403)
                    .body("code", equalTo("rest_forbidden"))
                    .body("message", equalTo("Sorry, you are not allowed to do that."));
        }
        assertThat(get(bob, hidden + "?context=edit").get("post").asLong()).isEqualTo(draft.value());
    }

    @Test
    void anotherSitesAttachmentIs404() {
        SiteId other = TestData.site(data);
        UserId editor = TestData.profile(data, "editor");
        TestData.member(data, other, editor, "[\"editor\"]");
        TestData.member(data, TestData.MAIN, editor, "[\"editor\"]");
        long theirs = MediaFixturesForRest.upload(other, editor, "o.jpg", false)
                .post()
                .id()
                .value();
        Http.as(bearer(editor))
                .get("/wp-json/wp/v2/media/" + theirs)
                .then()
                .statusCode(404)
                .body("code", equalTo("rest_post_invalid_id"))
                .body("message", equalTo("Invalid post ID."));
    }

    @Test
    void theMediaFeatureOffAnswers404() {
        SiteId site = TestData.site(data);
        String host = Jdbc.strings(data, "select domain from qp_site where id = ?", site.value())
                .get(0);
        TestData.member(data, site, bob, "[\"editor\"]");
        long id = MediaFixturesForRest.upload(site, bob, "f.jpg", false)
                .post()
                .id()
                .value();
        TestData.siteOption(data, site, "qp_features", "{\"media\": false}");
        Http.at(host, bearer(bob))
                .get("/wp-json/wp/v2/media/" + id)
                .then()
                .statusCode(404)
                .body("code", equalTo("rest_no_route"))
                .body("message", equalTo("No route was found matching the URL and request method."));
        TestData.siteOption(data, site, "qp_features", "{\"media\": true}");
        Http.at(host, bearer(bob))
                .get("/wp-json/wp/v2/media/" + id)
                .then()
                .statusCode(200)
                .body("id", equalTo((int) id));
    }

    @Test
    void theLinkHeaderIsTheAttachmentsPage() {
        long id = upload("l" + UUID.randomUUID().toString().substring(0, 8) + ".jpg")
                .post()
                .id()
                .value();
        String alternate = "<http://localhost/?attachment_id=" + id + ">; rel=\"alternate\"; type=text/html";
        Response item = Http.as(null).get("/wp-json/wp/v2/media/" + id);
        item.then().statusCode(200);
        assertThat(item.headers().getValues("Link")).containsExactly(alternate);
        Response head = Http.as(null).head("/wp-json/wp/v2/media/" + id);
        head.then().statusCode(200);
        assertThat(head.headers().getValues("Link")).containsExactly(alternate);
    }

    @Test
    void secureRequestsGetHttpsFileUrls() {
        MediaSnapshot jpeg = upload("s" + UUID.randomUUID().toString().substring(0, 8) + ".jpg");
        long id = jpeg.post().id().value();
        String stem = jpeg.attachedFile()
                .substring(
                        jpeg.attachedFile().lastIndexOf('/') + 1,
                        jpeg.attachedFile().length() - 4);
        // a stored size of the main file's proportions, so that the sizes and the srcset have URLs
        Jdbc.update(
                data,
                "update qp_media set sizes = '{\"thumbnail\":{\"file\":\"" + stem + "-32x24.jpg\",\"width\":32,"
                        + "\"height\":24,\"mime-type\":\"image/jpeg\",\"filesize\":100}}' where post_id = ?",
                id);
        JsonNode item = json(Http.as(null)
                .header("X-Forwarded-Proto", "https")
                .get("/wp-json/wp/v2/media/" + id)
                .then()
                .statusCode(200)
                .extract()
                .asString());
        String secure = "https://" + jpeg.url().substring("http://".length());
        assertThat(item.get("source_url").asText()).isEqualTo(secure);
        assertThat(item.at("/media_details/sizes/thumbnail/source_url").asText())
                .isEqualTo(secure.replace(stem + ".jpg", stem + "-32x24.jpg"));
        assertThat(item.at("/media_details/sizes/full/source_url").asText()).isEqualTo(secure);
        assertThat(item.at("/description/rendered").asText())
                .startsWith("<p class=\"attachment\"><a href='" + secure + "'><img")
                .contains(" src=\"" + secure + "\"", " srcset=\"" + secure + " 64w, ");
        assertThat(item.at("/guid/rendered").asText())
                .isEqualTo(jpeg.post().guid())
                .startsWith("http://");
        // a plain request keeps http
        assertThat(get(null, Long.toString(id)).get("source_url").asText()).isEqualTo(jpeg.url());
    }

    @Test
    void nestedFieldsOfAttachmentFieldsAreNotComputed() {
        long id = upload("n" + UUID.randomUUID().toString().substring(0, 8) + ".jpg")
                .post()
                .id()
                .value();
        for (String query : List.of(
                "_fields=description.raw&context=edit", "_fields=media_details.sizes", "_fields=caption.rendered")) {
            Http.as(bearer(bob))
                    .get("/wp-json/wp/v2/media/" + id + "?" + query)
                    .then()
                    .statusCode(200)
                    .body(equalTo("[]"));
        }
        JsonNode title = get(bob, id + "?_fields=title.raw&context=edit");
        assertThat(fieldNames(title)).containsExactly("title");
        assertThat(fieldNames(title.get("title"))).containsExactly("raw");
        // a top-level name computes the field
        assertThat(fieldNames(get(bob, id + "?_fields=media_details,description&context=edit")))
                .containsExactly("description", "media_details");
    }

    @Test
    void aPdfListsTheFallbackSizesAsMissing() {
        MediaSnapshot pdf = upload("p" + UUID.randomUUID().toString().substring(0, 8) + ".pdf");
        JsonNode item = get(bob, pdf.post().id().value() + "?context=edit");
        assertThat(item.get("missing_image_sizes").toString()).isEqualTo("[\"thumbnail\",\"medium\",\"large\"]");
        assertThat(item.get("media_type").asText()).isEqualTo("file");
        assertThat(item.get("mime_type").asText()).isEqualTo("application/pdf");
        assertThat(item.get("media_details").toString())
                .isEqualTo("{\"filesize\":" + pdf.fileSize() + ",\"sizes\":{}}");
        String title = pdf.post().title();
        assertThat(item.at("/description/rendered").asText())
                .isEqualTo("<p class=\"attachment\"><a href='" + pdf.url() + "'>" + title + "</a></p>\n");
        assertThat(item.at("/caption/rendered").asText()).isEqualTo("<p>" + title + "</p>\n");
    }

    @Test
    void aFailedImageShowsTheMainFileWithoutDimensions() {
        MediaSnapshot jpeg = upload("x" + UUID.randomUUID().toString().substring(0, 8) + ".jpg");
        long id = jpeg.post().id().value();
        String stem = jpeg.attachedFile()
                .substring(
                        jpeg.attachedFile().lastIndexOf('/') + 1,
                        jpeg.attachedFile().length() - 4);
        Jdbc.update(
                data,
                "update qp_media set processing = 'failed', sizes = '{\"thumbnail\":{\"file\":\"" + stem
                        + "-32x24.jpg\",\"width\":32,\"height\":24,\"mime-type\":\"image/jpeg\",\"filesize\":100}}'"
                        + " where post_id = ?",
                id);
        JsonNode item = get(bob, id + "?context=edit");
        assertThat(item.at("/description/raw").asText()).isEmpty();
        assertThat(item.at("/description/rendered").asText())
                .isEqualTo("<p class=\"attachment\"><a href='" + jpeg.url() + "'><img loading=\"lazy\""
                        + " decoding=\"async\" width=\"1\" height=\"1\" src=\"" + jpeg.url() + "\""
                        + " class=\"attachment-medium size-medium\" alt=\"\" /></a></p>\n");
        assertThat(item.get("media_details").toString()).isEqualTo("{}");
    }

    @Test
    void theGeneratedSlugOfANonAsciiTitleIsDecoded() {
        String prefix = "g" + UUID.randomUUID().toString().substring(0, 8);
        // Cyrillic "foto": the stored slug is percent-encoded, the generated one is not
        String foto = new String(new int[] {0x444, 0x43e, 0x442, 0x43e}, 0, 4);
        MediaSnapshot named = upload(prefix + "-" + foto + ".jpg");
        assertThat(named.post().slug()).isEqualTo(prefix + "-%d1%84%d0%be%d1%82%d0%be");
        JsonNode item = get(bob, named.post().id().value() + "?context=edit");
        assertThat(item.get("slug").asText()).isEqualTo(prefix + "-%d1%84%d0%be%d1%82%d0%be");
        assertThat(item.get("generated_slug").asText()).isEqualTo(prefix + "-" + foto);
    }

    @Test
    void anAttachmentPostWithoutAFileReadsAsOneWithoutMetadata() {
        // an attachment post written through the posts module alone (or past it) has no media row
        PostId bare = TestData.post(data, TestData.MAIN, bob, "attachment", "inherit");
        String guid = "http://localhost/?attachment_id=" + bare.value();
        Jdbc.update(data, "update qp_post set guid = ?, title = 'bare' where id = ?", guid, bare.value());
        JsonNode item = get(null, Long.toString(bare.value()));
        assertThat(item.get("media_details").toString()).isEqualTo("{}");
        assertThat(item.get("media_type").asText()).isEqualTo("file");
        assertThat(item.get("mime_type").asText()).isEmpty();
        assertThat(item.get("source_url").asText()).isEqualTo(guid);
        assertThat(item.get("filename").isNull()).isTrue();
        assertThat(item.get("filesize").isNull()).isTrue();
        assertThat(item.get("post").isNull()).isTrue();
        assertThat(item.at("/description/rendered").asText())
                .isEqualTo("<p class=\"attachment\"><a href='" + guid + "'>bare</a></p>\n");
        assertThat(item.at("/caption/rendered").asText()).isEqualTo("<p>bare</p>\n");
        JsonNode edited = get(bob, bare.value() + "?context=edit");
        assertThat(edited.get("missing_image_sizes").toString()).isEqualTo("[]");
        assertThat(edited.has("exif_orientation")).isFalse();
        assertThat(item.get("alt_text").asText()).isEmpty();
        // the stored alternative text of such a post is read from its own meta
        Jdbc.update(
                data,
                "update qp_post set meta = " + TestDb.json() + " where id = ?",
                "{\"_wp_attachment_image_alt\":[\"bare alt\"]}",
                bare.value());
        assertThat(get(null, Long.toString(bare.value())).get("alt_text").asText())
                .isEqualTo("bare alt");
        // a secure request switches the file URL taken from the guid to https; the guid itself keeps its scheme
        JsonNode secure = json(Http.as(null)
                .header("X-Forwarded-Proto", "https")
                .get("/wp-json/wp/v2/media/" + bare.value())
                .then()
                .statusCode(200)
                .extract()
                .asString());
        String https = "https://" + guid.substring("http://".length());
        assertThat(secure.get("source_url").asText()).isEqualTo(https);
        assertThat(secure.at("/description/rendered").asText())
                .isEqualTo("<p class=\"attachment\"><a href='" + https + "'>bare</a></p>\n");
        assertThat(secure.at("/guid/rendered").asText()).isEqualTo(guid);
        // a video whose cover is such a post shows the guid as its poster, switched to https on a secure request
        MediaSnapshot video = upload("v" + UUID.randomUUID().toString().substring(0, 8) + ".mp4");
        Jdbc.update(
                data,
                "update qp_post set meta = " + TestDb.json() + " where id = ?",
                "{\"_thumbnail_id\":[\"" + bare.value() + "\"]}",
                video.post().id().value());
        assertThat(get(null, Long.toString(video.post().id().value()))
                        .at("/description/rendered")
                        .asText())
                .contains(" poster=\"" + guid + "\" preload=\"metadata\"");
        assertThat(json(Http.as(null)
                                .header("X-Forwarded-Proto", "https")
                                .get("/wp-json/wp/v2/media/" + video.post().id().value())
                                .then()
                                .statusCode(200)
                                .extract()
                                .asString())
                        .at("/description/rendered")
                        .asText())
                .contains(" poster=\"" + https + "\" ");
    }

    // ---- helpers

    static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
