package org.quarkpress.testing.wprestparity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quarkpress.testing.wporacle.OracleJson;
import org.quarkpress.testing.wpscenario.ScenarioDatabase;
import org.quarkpress.testing.wpscenario.WordPressScenarioOracle;
import org.quarkpress.testing.wpscenario.WordPressTree;

/**
 * Records what WordPress 7.1.3 answers for the REST media references' verification lists: the routes reference's
 * Part G ({@code r01}-{@code r24}, plus {@code r26}, the upload header on a plain 201) and the responses reference's
 * Part G ({@code s01}-{@code s18}), with the OPTIONS bodies of the three media routes. Every item runs on a
 * subdirectory site of its own ({@code /er01/}, {@code /es01/}, ...) whose uploads are emptied before and after it,
 * records every exchange with {@link VerificationFacts#record} and every setup answer with {@link
 * VerificationFacts#note}, and switches off in its own {@code finally} every {@code qp_parity_*} switch it turns on.
 * Only expectations backed by recorded editor traffic or the design are asserted; everything else is recorded. Images
 * are uploaded with {@code generate_sub_sizes=false} (WordPress without an editor refuses a declared JPEG otherwise)
 * except in the items that need an editor, which run only when the server's PHP has GD (as the CI parity cell does) and
 * are skipped with a message otherwise, or fail under {@code -Dwp.media.require-editor=true}. Imagick, browser and
 * outbound-network items never run here.
 */
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
@TestMethodOrder(MethodOrderer.MethodName.class)
class MediaRestFactsDbTest {

    static ScenarioDatabase database;
    static WordPressRestSite wp;
    static RestClient client;
    static MediaFactsDbTest.Extensions extensions;

    /** The item the running test records under; null when it records nothing. */
    String item;

    /** The running item's site URL prefix ({@code /er01}); null for a test that is not an item. */
    String site;

    /** The running item's API root as WordPress writes it ({@code http://localhost/er01/wp-json/}). */
    String root;

    @BeforeAll
    static void start() throws Exception {
        assumeTrue(ScenarioDatabase.runtimeAvailable(), "ASSUMPTION: no container runtime");
        database = ScenarioDatabase.start();
        // Harness rule (as in the media facts run): the installing administrator is the current user while the
        // template network is created, so the network's upload_filetypes is written for that user.
        System.setProperty(WordPressScenarioOracle.INSTALLER_CURRENT_USER, "true");
        try {
            wp = WordPressRestSite.start(
                    WordPressTree.ensure(System.getProperty("wp.version")),
                    database,
                    System.getProperty("wp.php", "php"));
        } finally {
            System.clearProperty(WordPressScenarioOracle.INSTALLER_CURRENT_USER);
        }
        client = new RestClient(wp.root(), RestClient.applicationPasswords(wp));
        JsonNode found = wp.operation("extensions", args());
        extensions = new MediaFactsDbTest.Extensions(
                found.path("gd").asBoolean(),
                found.path("gd_webp").asBoolean(),
                found.path("gd_avif").asBoolean(),
                found.path("imagick").asBoolean(),
                found.path("dom").asBoolean(),
                found.path("exif").asBoolean(),
                found.path("fileinfo").asBoolean());
        VerificationFacts.note("x-media-rest-extensions", "extensions", found);
    }

    @AfterAll
    static void stop() {
        try {
            VerificationFacts.save();
        } finally {
            if (wp != null) {
                wp.close();
            }
            if (database != null) {
                database.close();
            }
        }
    }

    @BeforeEach
    void open(TestInfo info) throws Exception {
        String method = info.getTestMethod().orElseThrow().getName();
        boolean itemName = method.length() > 3
                && (method.charAt(0) == 'r' || method.charAt(0) == 's')
                && Character.isDigit(method.charAt(1))
                && Character.isDigit(method.charAt(2));
        item = itemName ? method.substring(0, 3) : null;
        site = null;
        root = null;
        if (item != null) {
            String path = "/e" + item + "/";
            wp.operation("create_site", args().put("path", path).put("title", item));
            wp.operation("clear_uploads", args().put("site", path));
            site = "/e" + item;
            root = "http://localhost" + site + "/wp-json/";
        }
    }

    @AfterEach
    void clearUploads() throws Exception {
        // Harness rule: the shared WordPress tree keeps no files of an item.
        if (site != null) {
            wp.operation("clear_uploads", args().put("site", site + "/"));
        }
    }

    // ---- routes reference, Part G ---------------------------------------------------------------------------------

    @Test
    void r01CreateIgnoresAuthorAndTitleOfTheRequest() throws Exception {
        // Recorded: whether description and parent (post) survive the failing field step, stored and in edit context.
        long parent = post("alice", "post", "publish", "r01 parent", args());
        Exchange created = upload(
                "alice",
                "r01.jpg",
                "image/jpeg",
                MediaFixtures.jpeg(64, 48),
                fields(
                        "author",
                        "999999",
                        "title",
                        "Kept?",
                        "caption",
                        "c",
                        "description",
                        "d",
                        "post",
                        String.valueOf(parent),
                        "generate_sub_sizes",
                        "false"));
        noteAttachment("r01.jpg", created);
        editView(created);
        Exchange withId = upload(
                "alice",
                "r01-id.jpg",
                "image/jpeg",
                MediaFixtures.jpeg(64, 48),
                fields(
                        "author",
                        "999999",
                        "title",
                        "Kept?",
                        "caption",
                        "c",
                        "description",
                        "d",
                        "post",
                        String.valueOf(parent),
                        "id",
                        "0",
                        "generate_sub_sizes",
                        "false"));
        noteAttachment("r01-id.jpg", withId);
        editView(withId);
    }

    /** Records the edit-context read of a 201 upload's attachment (description raw and rendered, post). */
    void editView(Exchange uploaded) throws Exception {
        if (uploaded.status() == 201) {
            send(RestCall.get(
                    "alice",
                    api("wp/v2/media/" + uploaded.json().get("id").asLong()
                            + "?context=edit&_fields=id,description,caption,title,author,post")));
        }
    }

    @Test
    void r02StatusWithoutPublishPosts() throws Exception {
        JsonNode role = wp.operation(
                "role",
                args().put("site", site + "/")
                        .put("name", "qp_uploader")
                        .set("caps", strings("upload_files", "read", "edit_posts")));
        VerificationFacts.note(item, "role", withoutPassword(role));
        Exchange uploader = upload(
                "uploader",
                "r02-private.jpg",
                "image/jpeg",
                MediaFixtures.jpeg(64, 48),
                fields("status", "private", "title", "Requested", "generate_sub_sizes", "false"));
        noteAttachment("r02-private.jpg", uploader);
        Exchange carol = upload(
                "carol",
                "r02-carol.jpg",
                "image/jpeg",
                MediaFixtures.jpeg(64, 48),
                fields("status", "private", "title", "Requested", "generate_sub_sizes", "false"));
        noteAttachment("r02-carol.jpg", carol);
    }

    @Test
    void r03FeaturedMediaZeroOnAnImage() throws Exception {
        Exchange refused = upload(
                "alice",
                "r03.jpg",
                "image/jpeg",
                MediaFixtures.jpeg(64, 48),
                fields("featured_media", "0", "generate_sub_sizes", "false"));
        JsonNode left = noteQuery("image/jpeg");
        for (JsonNode post : left.path("posts")) {
            long id = post.get("id").asLong();
            send(RestCall.get(
                    "alice",
                    api("wp/v2/media?context=edit&_fields=id,media_details,missing_image_sizes&include=" + id)));
            noteStored("attachment " + id, id);
        }
        VerificationFacts.note(item, "refused status", TextNode.valueOf(String.valueOf(refused.status())));
    }

    @Test
    void r04FeaturedMediaOnAudioAtCreate() throws Exception {
        long image = uploadImage("alice", "r04.jpg");
        Exchange audio = upload(
                "alice",
                "r04.mp3",
                "audio/mpeg",
                MediaFixtures.mp3(Map.of(), Optional.empty()),
                fields("featured_media", String.valueOf(image)));
        noteAttachment("r04.mp3", audio);
        Exchange invalid = upload(
                "alice",
                "r04-invalid.mp3",
                "audio/mpeg",
                MediaFixtures.mp3(Map.of(), Optional.empty()),
                fields("featured_media", "999999"));
        noteAttachment("r04-invalid.mp3", invalid);
        noteQuery("audio/mpeg");
    }

    @Test
    void r05FeaturedMediaOnUpdate() throws Exception {
        long image = uploadImage("alice", "r05.jpg");
        long other = uploadImage("alice", "r05-other.jpg");
        long audio = created(upload(
                "alice",
                "r05.mp3",
                "audio/mpeg",
                MediaFixtures.mp3(Map.of(), Optional.empty()),
                fields("featured_media", String.valueOf(image))));
        send(RestCall.json("alice", "PATCH", api("wp/v2/media/" + image), "{\"featured_media\":" + other + "}"));
        noteMeta("_thumbnail_id of the image", image, "_thumbnail_id");
        send(RestCall.json("alice", "PATCH", api("wp/v2/media/" + audio), "{\"featured_media\":999999}"));
        noteMeta("_thumbnail_id of the audio after 999999", audio, "_thumbnail_id");
        send(RestCall.json("alice", "PATCH", api("wp/v2/media/" + audio), "{\"featured_media\":0}"));
        noteMeta("_thumbnail_id of the audio after 0", audio, "_thumbnail_id");
        send(RestCall.get("alice", api("wp/v2/media/" + audio + "?context=edit")));
    }

    @Test
    void r06RawBodyUploads() throws Exception {
        byte[] jpeg = MediaFixtures.jpeg(64, 48);
        String target = api("wp/v2/media?generate_sub_sizes=false");
        List<String> rows = List.of(
                "attachment; filename=\"r06-photo.jpg\"",
                "attachment; filename=r06-bare.jpg",
                "inline; filename=\"r06-inline.jpg\"",
                "attachment",
                "attachment; FILENAME=\"r06-upper.jpg\"",
                "attachment; filename*=UTF-8''%C3%A9t%C3%A9.jpg",
                "attachment; filename=\"a;b.jpg\"",
                "attachment; filename=\"\"");
        for (String disposition : rows) {
            raw("alice", target, Optional.of("image/jpeg"), lines("Content-Disposition", disposition), jpeg);
        }
        raw(
                "alice",
                target,
                Optional.empty(),
                lines("Content-Disposition", "attachment; filename=\"r06-nt.jpg\""),
                jpeg);
        raw(
                "alice",
                target,
                Optional.of("image/jpeg"),
                lines("Content-Disposition", "attachment; filename=\"r06-empty.jpg\""),
                new byte[0]);
        raw(
                "alice",
                target,
                Optional.of("image/jpeg"),
                lines(
                        "Content-Disposition",
                        "attachment; filename=\"r06-first.jpg\"",
                        "attachment; filename=\"r06-last.jpg\""),
                jpeg);
        send(RestCall.json("alice", "POST", api("wp/v2/media"), "{\"title\":\"x\"}"));
        noteListing();
    }

    @Test
    void r07ContentMd5() throws Exception {
        byte[] jpeg = MediaFixtures.jpeg(64, 48);
        byte[] md5 = MessageDigest.getInstance("MD5").digest(jpeg);
        String lower = HexFormat.of().formatHex(md5);
        uploadWithHeader("r07-lower.jpg", jpeg, "Content-MD5", lower);
        uploadWithHeader("r07-upper.jpg", jpeg, "Content-MD5", lower.toUpperCase(Locale.ROOT));
        uploadWithHeader(
                "r07-base64.jpg", jpeg, "Content-MD5", Base64.getEncoder().encodeToString(md5));
        RestCall whole = RestCall.multipart(
                "alice",
                api("wp/v2/media"),
                "r07-whole.jpg",
                "image/jpeg",
                jpeg,
                fields("generate_sub_sizes", "false"));
        String bodyMd5 = HexFormat.of()
                .formatHex(MessageDigest.getInstance("MD5").digest(whole.body().orElseThrow()));
        sendUpload(whole.withHeader("Content-MD5", bodyMd5), "r07-whole.jpg", "image/jpeg", jpeg);
        uploadWithHeader("r07-empty.jpg", jpeg, "Content-MD5", "");
    }

    @Test
    void r08MultipartPartNames() throws Exception {
        byte[] jpeg = MediaFixtures.jpeg(64, 48);
        long parent = post("alice", "post", "publish", "r08 parent", args());
        multipart("upload part", List.of(filePart("upload", "r08-upload.jpg", "image/jpeg", jpeg), noSizesPart()));
        multipart("post field only", List.of(textPart("post", String.valueOf(parent))));
        multipart(
                "empty file part",
                List.of(filePart("file", "", "application/octet-stream", new byte[0]), noSizesPart()));
        multipart("file[]", List.of(filePart("file[]", "r08-list.jpg", "image/jpeg", jpeg), noSizesPart()));
        multipart("file[a]", List.of(filePart("file[a]", "r08-keyed.jpg", "image/jpeg", jpeg), noSizesPart()));
        noteListing();
    }

    @Test
    void r09FatalDuringTheSecondSize() throws Exception {
        requireEditor();
        Exchange crashed;
        try {
            option("qp_parity_fatal_on_second_size", "1");
            crashed = upload("alice", "r09.jpg", "image/jpeg", MediaFixtures.jpeg(1600, 1200), Map.of());
        } finally {
            option("qp_parity_fatal_on_second_size", "0");
        }
        Optional<String> id = crashed.header("X-WP-Upload-Attachment-ID");
        if (id.isEmpty()) {
            noteQuery("image/jpeg");
            return;
        }
        noteStored("after the crash", Long.parseLong(id.get()));
        send(RestCall.json(
                "alice",
                "POST",
                api("wp/v2/media/" + id.get() + "/post-process"),
                "{\"action\":\"create-image-subsizes\"}"));
        noteStored("after post-process", Long.parseLong(id.get()));
    }

    @Test
    void r10PostProcessChecks() throws Exception { // missing action precedes permission
        long jpeg = uploadImage("alice", "r10.jpg");
        Exchange missing = send(RestCall.json("anonymous", "POST", api("wp/v2/media/" + jpeg + "/post-process"), "{}"));
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.json().get("code").asText()).isEqualTo("rest_missing_callback_param");
        assertThat(missing.json().get("message").asText()).isEqualTo("Missing parameter(s): action");
        Exchange get = send(RestCall.get("alice", api("wp/v2/media/" + jpeg + "/post-process")));
        assertThat(get.status()).isEqualTo(404);
        assertThat(get.json().get("code").asText()).isEqualTo("rest_no_route");
        // recorded: action=foo, a post id, carol on bob's image, a PDF, a {filesize}-only HEIC, OPTIONS, Allow
        send(RestCall.json("alice", "POST", api("wp/v2/media/" + jpeg + "/post-process"), "{\"action\":\"foo\"}"));
        long post = post("alice", "post", "publish", "r10 post", args());
        postProcess("alice", post);
        long bobs = uploadImage("bob", "r10-bob.jpg");
        postProcess("carol", bobs);
        long pdf = created(upload("alice", "r10.pdf", "application/pdf", MediaFixtures.pdf(), Map.of()));
        postProcess("alice", pdf);
        noteStored("pdf after post-process", pdf);
        long heic = created(upload(
                "alice", "r10.heic", "image/heic", MediaFixtures.heif("heic"), fields("generate_sub_sizes", "false")));
        wp.operation(
                "set_meta",
                args().put("site", site + "/")
                        .put("post", heic)
                        .put("key", "_wp_attachment_metadata")
                        .set("value", args().put("filesize", 123)));
        postProcess("alice", heic);
        noteStored("filesize-only heic after post-process", heic);
        send(new RestCall(
                "alice",
                "OPTIONS",
                api("wp/v2/media/" + jpeg + "/post-process"),
                Map.of(),
                Optional.empty(),
                Optional.empty()));
        postProcess("alice", jpeg);
    }

    @Test
    void r11CollectionFilters() throws Exception {
        upload(
                "alice",
                "IMG_r11.jpg",
                "image/jpeg",
                MediaFixtures.jpeg(64, 48),
                fields("title", "Sunset", "generate_sub_sizes", "false"));
        upload("alice", "r11.pdf", "application/pdf", MediaFixtures.pdf(), fields("title", "Report"));
        upload(
                "alice",
                "r11.mp3",
                "audio/mpeg",
                MediaFixtures.mp3(Map.of(), Optional.empty()),
                fields("title", "Song"));
        for (String query : List.of(
                "status=any",
                "status=publish",
                "media_type=image&mime_type=application/pdf",
                "mime_type=foo/bar",
                "mime_type=image/jpg",
                "media_type=font",
                "search=2026",
                "search=scaled",
                "search=IMG&search_columns=post_title",
                "search=-IMG_r11",
                "search=sites")) {
            send(RestCall.get("alice", api("wp/v2/media?" + query)));
        }
        send(RestCall.get("erin", api("wp/v2/media?status=private")));
    }

    @Test
    void r12VisibilityByParent() throws Exception {
        Map<String, Long> attachments = new LinkedHashMap<>();
        attachments.put("draft parent", attachedImage(post("bob", "post", "draft", "r12 draft", args())));
        attachments.put("private parent", attachedImage(post("bob", "post", "private", "r12 private", args())));
        long trashed = post("bob", "post", "publish", "r12 trashed", args());
        attachments.put("trashed published parent", attachedImage(trashed));
        wp.operation("trash_post", args().put("site", site + "/").put("id", trashed));
        long deleted = post("bob", "post", "publish", "r12 deleted", args());
        attachments.put("deleted parent", attachedImage(deleted));
        wp.operation(
                "delete_post", args().put("site", site + "/").put("id", deleted).put("force", true));
        try {
            option("qp_parity_hidden_type", "1");
            attachments.put(
                    "qp_hidden published parent",
                    attachedImage(post("bob", "qp_hidden", "publish", "r12 hidden", args())));
            attachments.put("no parent", uploadImage("alice", "r12-none.jpg"));
            ObjectNode ids = args();
            attachments.forEach(ids::put);
            VerificationFacts.note(item, "attachments", ids);
            for (long id : attachments.values()) {
                noteStored("attachment " + id, id);
            }
            for (String as : List.of("anonymous", "erin", "carol")) {
                for (long id : attachments.values()) {
                    send(RestCall.get(as, api("wp/v2/media/" + id)));
                }
                send(RestCall.get(as, api("wp/v2/media?per_page=100")));
            }
        } finally {
            option("qp_parity_hidden_type", "0");
        }
    }

    @Test
    void r13PasswordAndAlternateLink() throws Exception {
        long image = uploadImage("carol", "r13.jpg");
        send(RestCall.get("alice", api("wp/v2/media/" + image + "?password=x")));
        send(RestCall.get("anonymous", api("wp/v2/media/" + image)));
    }

    @Test
    void r14ParentChangesOnUpdate() throws Exception {
        long draft = post("bob", "post", "draft", "r14 draft", args());
        long image = uploadImage("carol", "r14.jpg");
        send(RestCall.json("carol", "PATCH", api("wp/v2/media/" + image), "{\"post\":" + draft + "}"));
        send(RestCall.json("carol", "PATCH", api("wp/v2/media/" + image), "{\"post\":999999}"));
        send(RestCall.get("anonymous", api("wp/v2/media/" + image)));
        long post = post("carol", "post", "draft", "r14 own", args().put("post_content", "first"));
        JsonNode revision = wp.operation(
                "revision",
                args().put("site", site + "/")
                        .put("as", "carol")
                        .put("id", post)
                        .put("title", "r14 own, edited")
                        .put("content", "second"));
        VerificationFacts.note(item, "revision", revision);
        send(RestCall.json(
                "carol",
                "PATCH",
                api("wp/v2/media/" + image),
                "{\"post\":" + revision.get("id").asLong() + "}"));
        noteStored("attachment", image);
    }

    @Test
    void r15UpdateIgnoresAFilePart() throws Exception {
        long image = uploadImage("alice", "r15.jpg");
        RestCall replace = RestCall.multipart(
                "alice",
                api("wp/v2/media/" + image),
                "r15-other.png",
                "image/png",
                MediaFixtures.png(32, 24),
                fields("generate_sub_sizes", "false"));
        sendUpload(replace, "r15-other.png", "image/png", MediaFixtures.png(32, 24));
        send(RestCall.get("alice", api("wp/v2/media/" + image + "?context=edit")));
        noteStored("attachment", image);
    }

    @Test
    void r16StatusWrites() throws Exception {
        for (String status : List.of("inherit", "draft", "private", "trash")) {
            upload(
                    "alice",
                    "r16-" + status + ".jpg",
                    "image/jpeg",
                    MediaFixtures.jpeg(64, 48),
                    fields("status", status, "generate_sub_sizes", "false"));
        }
        Exchange future = upload(
                "alice",
                "r16-future.jpg",
                "image/jpeg",
                MediaFixtures.jpeg(64, 48),
                fields("date", "2030-01-01T10:00:00", "generate_sub_sizes", "false"));
        noteAttachment("r16-future.jpg", future);
        long privateOne = created(upload(
                "alice",
                "r16-private-2.jpg",
                "image/jpeg",
                MediaFixtures.jpeg(64, 48),
                fields("status", "private", "generate_sub_sizes", "false")));
        send(RestCall.json("alice", "PATCH", api("wp/v2/media/" + privateOne), "{\"status\":\"inherit\"}"));
        send(RestCall.json("alice", "PATCH", api("wp/v2/media/" + privateOne), "{\"status\":\"publish\"}"));
        noteStored("after publish", privateOne);
    }

    @Test
    void r17BatchIsRefused() throws Exception {
        long image = uploadImage("alice", "r17.jpg");
        ArrayNode requests = OracleJson.MAPPER.createArrayNode();
        requests.addObject()
                .put("method", "PATCH")
                .put("path", "/wp/v2/media/" + image)
                .set("body", args().put("alt_text", "batched"));
        JsonNode batch = wp.operation("batch", args().put("as", "alice").set("requests", requests));
        send(RestCall.json(
                batch.get("as").asText(),
                "POST",
                api("batch/v1"),
                OracleJson.MAPPER.writeValueAsString(batch.get("body"))));
        send(new RestCall("alice", "OPTIONS", api("wp/v2/media"), Map.of(), Optional.empty(), Optional.empty()));
    }

    @Test
    void r18DeleteWithoutForceIs501() throws Exception { // design: no media trash, delete needs force
        long carolsImage = uploadImage("carol", "r18.jpg");
        long bobsImage = uploadImage("bob", "r18-bob.jpg");
        Exchange delete = send(RestCall.json("carol", "DELETE", api("wp/v2/media/" + carolsImage), "{}"));
        assertThat(delete.status()).isEqualTo(501);
        assertThat(delete.text())
                .isEqualTo("{\"code\":\"rest_trash_not_supported\",\"message\":\"The post does not"
                        + " support trashing. Set 'force=true' to delete.\",\"data\":{\"status\":501}}");
        // recorded: ?force=true, carol on bob's, a post id, then qp_parity_media_trashable: trash, then 410
        send(RestCall.json("carol", "DELETE", api("wp/v2/media/" + carolsImage + "?force=true"), "{}"));
        send(RestCall.json("carol", "DELETE", api("wp/v2/media/" + bobsImage), "{}"));
        long post = post("carol", "post", "publish", "r18 post", args());
        send(RestCall.json("carol", "DELETE", api("wp/v2/media/" + post), "{}"));
        long trashable = uploadImage("carol", "r18-trash.jpg");
        try {
            option("qp_parity_media_trashable", "1");
            send(RestCall.json("carol", "DELETE", api("wp/v2/media/" + trashable), "{}"));
            send(RestCall.json("carol", "DELETE", api("wp/v2/media/" + trashable), "{}"));
        } finally {
            option("qp_parity_media_trashable", "0");
        }
        noteStored("trashed", trashable);
    }

    @Test
    void r19UrlMode() throws Exception {
        send(RestCall.json("alice", "POST", api("wp/v2/media"), "{\"url\":\"http://127.0.0.1/x.jpg\"}"));
        send(RestCall.json("alice", "POST", api("wp/v2/media"), "{\"url\":\"https://example.com/notes/r19.txt\"}"));
        assumeTrue(false, "SKIPPED (no outbound network in the harness): recorded as unobservable");
    }

    @Test
    void r20EditorRecoveryLoop() {
        assumeTrue(false, "SKIPPED: the editor's recovery loop runs in a browser; the admin slice records it");
    }

    @Test
    void r21AllowOfACollectionReadNamingAParent() throws Exception {
        long draft = post("bob", "post", "draft", "r21 draft", args());
        send(RestCall.get("carol", api("wp/v2/media?post=" + draft)));
        send(RestCall.get("carol", api("wp/v2/media")));
    }

    @Test
    void r22StickyOnCreate() throws Exception {
        sendUpload(
                RestCall.multipart(
                        "carol",
                        api("wp/v2/media?sticky=1"),
                        "r22.jpg",
                        "image/jpeg",
                        MediaFixtures.jpeg(64, 48),
                        fields("generate_sub_sizes", "false")),
                "r22.jpg",
                "image/jpeg",
                MediaFixtures.jpeg(64, 48));
    }

    @Test
    void r23EditRoute() throws Exception {
        Exchange uploaded = upload(
                "alice", "r23.jpg", "image/jpeg", MediaFixtures.jpeg(64, 48), fields("generate_sub_sizes", "false"));
        long image = created(uploaded);
        String source = uploaded.json().get("source_url").asText();
        send(RestCall.json(
                "alice",
                "POST",
                api("wp/v2/media/" + image + "/edit"),
                "{\"src\":" + OracleJson.MAPPER.writeValueAsString(source) + "}"));
        requireEditor();
        long large = created(upload("alice", "r23-large.jpg", "image/jpeg", MediaFixtures.jpeg(400, 300), Map.of()));
        String largeSource = send(RestCall.get("alice", api("wp/v2/media/" + large)))
                .json()
                .get("source_url")
                .asText();
        Exchange cropped = send(RestCall.json(
                "alice",
                "POST",
                api("wp/v2/media/" + large + "/edit"),
                "{\"src\":" + OracleJson.MAPPER.writeValueAsString(largeSource)
                        + ",\"modifiers\":[{\"type\":\"crop\",\"args\":{\"left\":10,\"top\":10,\"width\":50,"
                        + "\"height\":50}}]}"));
        noteAttachment("cropped", cropped);
    }

    @Test
    void r24MultisiteSizeCheckOfAMultipartUpload() throws Exception {
        VerificationFacts.note(
                item,
                "upload_space_check_disabled",
                wp.operation("network_option", args().put("name", "upload_space_check_disabled")));
        VerificationFacts.note(
                item, "fileupload_maxk", wp.operation("network_option", args().put("name", "fileupload_maxk")));
        byte[] large = padded(MediaFixtures.jpeg(64, 48), 3 * 1024 * 1024);
        Exchange multipart = upload("alice", "r24.jpg", "image/jpeg", large, fields("generate_sub_sizes", "false"));
        noteAttachment("r24.jpg", multipart);
        assumeTrue(false, "SKIPPED (no outbound network in the harness): recorded as unobservable");
    }

    @Test
    void r26TheUploadHeaderIsOnThe201Only() throws Exception { // recorded editor traffic: header on the 201
        Exchange created = upload(
                "alice", "r26.jpg", "image/jpeg", MediaFixtures.jpeg(64, 48), fields("generate_sub_sizes", "false"));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.header("X-WP-Upload-Attachment-ID"))
                .contains(created.json().get("id").asText());
        assertThat(created.header("Location"))
                .contains(root + "wp/v2/media/" + created.json().get("id").asText());
        upload("alice", "r26.svg", "image/svg+xml", MediaFactsDbTest.svg(), Map.of());
    }

    // ---- responses reference, Part G ------------------------------------------------------------------------------

    @Test
    void s01LinkAndPermalinkTemplate() throws Exception {
        long hello = post("alice", "post", "publish", "Hello world", args().put("post_name", "hello-world"));
        long parentPage = post("alice", "page", "publish", "Parent page", args());
        long childPage = post("alice", "page", "publish", "Child page", args().put("post_parent", parentPage));
        long draft = post("alice", "post", "draft", "s01 draft", args());
        long trashed = post("alice", "post", "publish", "s01 trashed", args());
        long block = post("alice", "wp_block", "publish", "s01 block", args());
        Map<String, Long> images = new LinkedHashMap<>();
        images.put("a unattached", uploadImage("alice", "s01-a.jpg"));
        images.put("b published post", attachedImage(hello));
        images.put("c child page", attachedImage(childPage));
        images.put("d draft", attachedImage(draft));
        images.put("e trashed post", attachedImage(trashed));
        wp.operation("trash_post", args().put("site", site + "/").put("id", trashed));
        images.put("f wp_block", attachedImage(block));
        images.put(
                "g private",
                created(upload(
                        "alice",
                        "s01-g.jpg",
                        "image/jpeg",
                        MediaFixtures.jpeg(64, 48),
                        fields("status", "private", "generate_sub_sizes", "false"))));
        ObjectNode ids = args();
        images.forEach(ids::put);
        VerificationFacts.note(item, "attachments", ids);
        for (long id : images.values()) {
            send(RestCall.get("alice", api("wp/v2/media/" + id + "?context=edit")));
        }
        long privateOne = images.get("g private");
        send(RestCall.get("anonymous", api("wp/v2/media/" + privateOne)));
        send(RestCall.get("anonymous", api("wp/v2/media/" + privateOne + "?context=edit")));
        wp.operation(
                "option",
                args().put("site", site + "/")
                        .put("name", "permalink_structure")
                        .put("value", ""));
        for (String key : List.of("a unattached", "b published post")) {
            // In plain mode the API is reached through ?rest_route= (/wp-json/ answers a redirect there).
            send(RestCall.get("alice", site + "/?rest_route=/wp/v2/media/" + images.get(key) + "&context=edit"));
        }
    }

    @Test
    void s02NumericSlug() throws Exception {
        long hello = post("alice", "post", "publish", "Hello world", args().put("post_name", "hello-world"));
        long image = created(upload(
                "alice",
                "2024.jpg",
                "image/jpeg",
                MediaFixtures.jpeg(64, 48),
                fields("post", String.valueOf(hello), "generate_sub_sizes", "false")));
        send(RestCall.get("alice", api("wp/v2/media/" + image + "?context=edit")));
    }

    @Test
    void s03DescriptionAndCaptionRendered() throws Exception {
        long image = uploadImage("alice", "s03.jpg");
        send(RestCall.json(
                "alice", "PUT", api("wp/v2/media/" + image), "{\"description\":\"Hello world\",\"caption\":\"\"}"));
        send(RestCall.get("alice", api("wp/v2/media/" + image + "?context=edit")));
        requireEditor();
        long sized = created(upload("alice", "s03-sized.jpg", "image/jpeg", MediaFixtures.jpeg(1600, 1200), Map.of()));
        send(RestCall.json(
                "alice", "PUT", api("wp/v2/media/" + sized), "{\"description\":\"Hello world\",\"caption\":\"\"}"));
        send(RestCall.get("alice", api("wp/v2/media/" + sized + "?context=edit")));
    }

    @Test
    void s04AudioShortcodeSequence() throws Exception {
        for (int n = 1; n <= 3; n++) {
            upload(
                    "alice",
                    "s04-" + n + ".mp3",
                    "audio/mpeg",
                    MediaFixtures.mp3(Map.of(), Optional.empty()),
                    fields("caption", ""));
        }
        send(RestCall.get("alice", api("wp/v2/media?media_type=audio&per_page=10")));
    }

    @Test
    void s05ImageMarkupWithGd() throws Exception {
        requireEditor();
        long jpeg = created(upload("alice", "s05.jpg", "image/jpeg", MediaFixtures.jpeg(4000, 3000), Map.of()));
        send(RestCall.get("alice", api("wp/v2/media/" + jpeg + "?context=view")));
        long png = created(upload("alice", "s05.png", "image/png", MediaFixtures.png(600, 400), Map.of()));
        send(RestCall.get("alice", api("wp/v2/media/" + png + "?context=view")));
    }

    @Test
    void s06NestedFields() throws Exception {
        long image = uploadImage("alice", "s06.jpg");
        for (String query : List.of(
                "_fields=description.raw&context=edit",
                "_fields=media_details.sizes",
                "_fields=caption.rendered",
                "_fields=title.raw&context=edit")) {
            send(RestCall.get("alice", api("wp/v2/media/" + image + "?" + query)));
        }
    }

    @Test
    void s07ScaledWithGd() throws Exception {
        requireEditor();
        Exchange uploaded = upload("alice", "s07.jpg", "image/jpeg", MediaFixtures.jpeg(4000, 3000), Map.of());
        noteAttachment("s07.jpg", uploaded);
        send(RestCall.get("alice", api("wp/v2/media/" + created(uploaded) + "?context=edit")));
    }

    @Test
    void s08MissingSizeFileWithGd() throws Exception {
        requireEditor();
        long image = created(upload("alice", "s08.jpg", "image/jpeg", MediaFixtures.jpeg(1600, 1200), Map.of()));
        JsonNode stored =
                wp.operation("attachment", args().put("site", site + "/").put("id", image));
        VerificationFacts.note(item, "before", stored);
        String attached = stored.path("meta").path("_wp_attached_file").path(0).asText();
        String folder = attached.contains("/") ? attached.substring(0, attached.lastIndexOf('/') + 1) : "";
        String medium = stored.path("metadata")
                .path("sizes")
                .path("medium")
                .path("file")
                .asText();
        VerificationFacts.note(
                item,
                "removed medium",
                wp.operation("remove_upload", args().put("site", site + "/").put("file", folder + medium)));
        send(RestCall.get("alice", api("wp/v2/media/" + image + "?context=edit")));
    }

    @Test
    void s09MetadataDeleted() throws Exception {
        long image = uploadImage("alice", "s09.jpg");
        deleteMetadata(image);
        send(RestCall.get("alice", api("wp/v2/media/" + image + "?context=edit")));
        requireEditor();
        long sized = created(upload("alice", "s09-sized.jpg", "image/jpeg", MediaFixtures.jpeg(1600, 1200), Map.of()));
        deleteMetadata(sized);
        send(RestCall.get("alice", api("wp/v2/media/" + sized + "?context=edit")));
    }

    @Test
    void s10Https() throws Exception {
        long image = uploadImage("alice", "s10.jpg");
        send(RestCall.get("alice", api("wp/v2/media/" + image + "?context=edit")));
        send(RestCall.get("alice", api("wp/v2/media/" + image + "?context=edit"))
                .withHeader("X-QP-Parity-Https", "1"));
    }

    @Test
    void s11FeaturedMediaOnPostsByType() throws Exception {
        long jpeg = uploadImage("alice", "s11.jpg");
        Map<String, Long> others = new LinkedHashMap<>();
        try {
            option("qp_parity_allow_svg", "1");
            others.put("svg", created(upload("alice", "s11.svg", "image/svg+xml", MediaFactsDbTest.svg(), Map.of())));
        } finally {
            option("qp_parity_allow_svg", "0");
        }
        try {
            option("qp_parity_no_unsupported_refusal", "1");
            others.put(
                    "heic",
                    created(upload(
                            "alice",
                            "s11.heic",
                            "image/heic",
                            MediaFixtures.heif("heic"),
                            fields("generate_sub_sizes", "false"))));
            others.put(
                    "tiff",
                    created(upload(
                            "alice",
                            "s11.tif",
                            "image/tiff",
                            MediaFixtures.tiff(64, 48),
                            fields("generate_sub_sizes", "false"))));
        } finally {
            option("qp_parity_no_unsupported_refusal", "0");
        }
        others.put(
                "mp3",
                created(upload(
                        "alice", "s11.mp3", "audio/mpeg", MediaFixtures.mp3(Map.of(), Optional.empty()), Map.of())));
        others.put("pdf", created(upload("alice", "s11.pdf", "application/pdf", MediaFixtures.pdf(), Map.of())));
        long post = post("alice", "post", "publish", "s11 post", args());
        for (Map.Entry<String, Long> other : others.entrySet()) {
            send(RestCall.json("alice", "PUT", api("wp/v2/posts/" + post), "{\"featured_media\":" + jpeg + "}"));
            send(RestCall.json(
                    "alice", "PUT", api("wp/v2/posts/" + post), "{\"featured_media\":" + other.getValue() + "}"));
            noteMeta("_thumbnail_id after " + other.getKey(), post, "_thumbnail_id");
        }
    }

    @Test
    void s12FeaturedMediaLinkAndEmbed() throws Exception {
        long unattached = uploadImage("alice", "s12-a.jpg");
        long otherDraft = post("alice", "post", "draft", "s12 other draft", args());
        long attachedElsewhere = attachedImage(otherDraft);
        long privateImage = created(upload(
                "alice",
                "s12-c.jpg",
                "image/jpeg",
                MediaFixtures.jpeg(64, 48),
                fields("status", "private", "generate_sub_sizes", "false")));
        Map<String, Long> posts = new LinkedHashMap<>();
        posts.put("a unattached", post("alice", "post", "publish", "s12 a", args().put("thumbnail", unattached)));
        posts.put(
                "b attached to a draft",
                post("alice", "post", "publish", "s12 b", args().put("thumbnail", attachedElsewhere)));
        posts.put("c private", post("alice", "post", "publish", "s12 c", args().put("thumbnail", privateImage)));
        ObjectNode ids = args();
        posts.forEach(ids::put);
        VerificationFacts.note(item, "posts", ids);
        for (String as : List.of("anonymous", "alice")) {
            for (long post : posts.values()) {
                send(RestCall.get(as, api("wp/v2/posts/" + post + "?_embed=wp:featuredmedia")));
            }
        }
    }

    @Test
    void s13FeaturedMediaOnAttachments() throws Exception {
        upload(
                "alice",
                "s13.jpg",
                "image/jpeg",
                MediaFixtures.jpeg(64, 48),
                fields("featured_media", "0", "generate_sub_sizes", "false"));
        noteQuery("image/jpeg");
        long image = uploadImage("alice", "s13-image.jpg");
        long audio = created(upload(
                "alice",
                "s13.mp3",
                "audio/mpeg",
                MediaFixtures.mp3(Map.of(), Optional.empty()),
                fields("featured_media", String.valueOf(image))));
        long other = uploadImage("alice", "s13-other.jpg");
        send(RestCall.json("alice", "PUT", api("wp/v2/media/" + image), "{\"featured_media\":" + other + "}"));
        send(RestCall.json("alice", "PUT", api("wp/v2/media/" + audio), "{\"featured_media\":" + other + "}"));
        noteMeta("_thumbnail_id of the audio", audio, "_thumbnail_id");
        noteMeta("_thumbnail_id of the image", image, "_thumbnail_id");
    }

    @Test
    void s14AttachedToEdgeCases() throws Exception {
        try {
            option("qp_parity_hidden_type", "1");
            long hidden = attachedImage(post("alice", "qp_hidden", "publish", "s14 hidden", args()));
            send(RestCall.get("alice", api("wp/v2/media/" + hidden)));
            send(RestCall.get("anonymous", api("wp/v2/media/" + hidden + "?_embed=wp:attached-to")));
        } finally {
            option("qp_parity_hidden_type", "0");
        }
        long block = attachedImage(post("alice", "wp_block", "publish", "s14 block", args()));
        send(RestCall.get("anonymous", api("wp/v2/media/" + block + "?_embed=wp:attached-to")));
        long deletedParent = post("alice", "post", "publish", "s14 deleted", args());
        long orphan = attachedImage(deletedParent);
        wp.operation(
                "delete_post",
                args().put("site", site + "/").put("id", deletedParent).put("force", true));
        send(RestCall.get("alice", api("wp/v2/media/" + orphan)));
        noteStored("after the parent was deleted", orphan);
        long trashedParent = post("alice", "post", "publish", "s14 trashed", args());
        long kept = attachedImage(trashedParent);
        wp.operation("trash_post", args().put("site", site + "/").put("id", trashedParent));
        send(RestCall.get("alice", api("wp/v2/media/" + kept)));
        long bobsDraft = post("bob", "post", "draft", "s14 bob's draft", args());
        long carols = uploadImage("carol", "s14-carol.jpg");
        send(RestCall.json("carol", "PATCH", api("wp/v2/media/" + carols), "{\"post\":" + bobsDraft + "}"));
        send(RestCall.get("carol", api("wp/v2/media/" + carols + "?_embed=wp:attached-to")));
    }

    @Test
    void s15HeicOnAConvertingEditor() {
        assumeTrue(false, MediaFactsDbTest.IMAGICK_SKIP);
    }

    @Test
    void s16PdfPreviews() {
        assumeTrue(false, MediaFactsDbTest.IMAGICK_SKIP);
    }

    @Test
    void s17OptionsOfTheMediaRoutes() throws Exception {
        long image = uploadImage("alice", "s17.jpg");
        Exchange collection = options("wp/v2/media");
        VerificationFacts.saveOptions("/wp/v2/media", collection);
        Exchange single = options("wp/v2/media/" + image);
        VerificationFacts.saveOptions("/wp/v2/media/{id}", single);
        Exchange postProcess = options("wp/v2/media/" + image + "/post-process");
        VerificationFacts.saveOptions("/wp/v2/media/{id}/post-process", postProcess);
        assertThat(postProcess.status()).isEqualTo(200);
        assertThat(postProcess.json().has("schema")).isFalse();
        assertThat(collection.status()).isEqualTo(200);
        assertThat(collection.json().get("endpoints")).isNotEmpty();
        for (JsonNode endpoint : collection.json().get("endpoints")) {
            assertThat(endpoint.has("allow_batch")).isFalse();
        }
    }

    @Test
    void s18CoverArt() throws Exception {
        Exchange audio = sendUpload(
                RestCall.multipart(
                        "alice",
                        api("wp/v2/media?_embed=wp:featuredmedia"),
                        "s18.mp3",
                        "audio/mpeg",
                        MediaFixtures.mp3(Map.of("title", "Covered"), Optional.of(MediaFixtures.jpeg(32, 32))),
                        Map.of()),
                "s18.mp3",
                "audio/mpeg",
                MediaFixtures.mp3(Map.of("title", "Covered"), Optional.of(MediaFixtures.jpeg(32, 32))));
        noteAttachment("s18.mp3", audio);
        Exchange video = sendUpload(
                RestCall.multipart(
                        "alice",
                        api("wp/v2/media?_embed=wp:featuredmedia"),
                        "s18.mp4",
                        "video/mp4",
                        MediaFixtures.mp4(64, 48, 1),
                        Map.of()),
                "s18.mp4",
                "video/mp4",
                MediaFixtures.mp4(64, 48, 1));
        noteAttachment("s18.mp4", video);
        if (audio.status() == 201) {
            send(RestCall.get(
                    "alice", api("wp/v2/media/" + audio.json().get("id").asLong())));
        }
        noteListing();
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    /** Skips the item without GD, or fails it under {@code -Dwp.media.require-editor=true}. */
    static void requireEditor() {
        extensions.requireEditor();
    }

    /** A target under the item site's API root ({@code /er01/wp-json/<route>}). */
    String api(String route) {
        return site + "/wp-json/" + route;
    }

    /** Sends the call and records the exchange under the running item. */
    Exchange send(RestCall call) throws Exception {
        Exchange exchange = client.send(call);
        if (item != null) {
            VerificationFacts.record(item, exchange);
        }
        return exchange;
    }

    Exchange options(String route) throws Exception {
        return send(new RestCall("alice", "OPTIONS", api(route), Map.of(), Optional.empty(), Optional.empty()));
    }

    /** A multipart upload to the item site's media route. */
    Exchange upload(String as, String name, String type, byte[] bytes, Map<String, String> fields) throws Exception {
        return sendUpload(RestCall.multipart(as, api("wp/v2/media"), name, type, bytes, fields), name, type, bytes);
    }

    /** Sends an upload and records it with the file summarised (name, type, size, SHA-256) instead of its bytes. */
    Exchange sendUpload(RestCall call, String name, String type, byte[] bytes) throws Exception {
        Exchange exchange = client.send(call);
        String summary = "file=\"" + name + "\" type=" + type + " bytes=" + bytes.length + " sha256=" + sha256(bytes);
        record(call, summary, exchange);
        return exchange;
    }

    void record(RestCall call, String summary, Exchange exchange) {
        RestCall recorded = new RestCall(
                call.as(),
                call.method(),
                call.target(),
                call.headers(),
                call.contentType(),
                Optional.of(summary.getBytes(StandardCharsets.UTF_8)),
                call.headerLines());
        VerificationFacts.record(
                item,
                new Exchange(recorded, exchange.status(), exchange.headers(), exchange.body(), exchange.sentAt()));
    }

    Exchange uploadWithHeader(String name, byte[] bytes, String header, String value) throws Exception {
        RestCall call = RestCall.multipart(
                        "alice", api("wp/v2/media"), name, "image/jpeg", bytes, fields("generate_sub_sizes", "false"))
                .withHeader(header, value);
        return sendUpload(call, name, "image/jpeg", bytes);
    }

    /** A raw-body upload, recorded with the body summarised. */
    Exchange raw(String as, String target, Optional<String> type, Map<String, List<String>> lines, byte[] bytes)
            throws Exception {
        RestCall call = RestCall.raw(as, target, type, lines, bytes);
        Exchange exchange = client.send(call);
        record(call, "raw: bytes=" + bytes.length + " sha256=" + sha256(bytes), exchange);
        return exchange;
    }

    /** A JPEG upload without sub-sizes that must succeed; answers the new id. */
    long uploadImage(String as, String name) throws Exception {
        return created(
                upload(as, name, "image/jpeg", MediaFixtures.jpeg(64, 48), fields("generate_sub_sizes", "false")));
    }

    /** {@link #uploadImage} attached to {@code parent} (uploaded by alice). */
    long attachedImage(long parent) throws Exception {
        return created(upload(
                "alice",
                "attached-" + parent + ".jpg",
                "image/jpeg",
                MediaFixtures.jpeg(64, 48),
                fields("post", String.valueOf(parent), "generate_sub_sizes", "false")));
    }

    /** The id of a 201 answer; a setup upload that fails ends the item with what WordPress said. */
    static long created(Exchange exchange) {
        assertThat(exchange.status())
                .as("setup upload %s answered %s", exchange.call().target(), exchange.text())
                .isEqualTo(201);
        return exchange.json().get("id").asLong();
    }

    /** Inserts a post on the item site (rest-setup.php {@code post}); answers its id. */
    long post(String as, String type, String status, String title, ObjectNode fields) throws Exception {
        ObjectNode input = args().put("site", site + "/")
                .put("as", as)
                .put("type", type)
                .put("status", status)
                .put("title", title);
        input.set("fields", fields);
        return wp.operation("post", input).get("id").asLong();
    }

    void option(String name, String value) throws Exception {
        wp.operation("option", args().put("site", site + "/").put("name", name).put("value", value));
    }

    void postProcess(String as, long id) throws Exception {
        send(RestCall.json(
                as, "POST", api("wp/v2/media/" + id + "/post-process"), "{\"action\":\"create-image-subsizes\"}"));
    }

    void deleteMetadata(long id) throws Exception {
        VerificationFacts.note(
                item,
                "delete_meta " + id,
                wp.operation(
                        "delete_meta",
                        args().put("site", site + "/").put("post", id).put("key", "_wp_attachment_metadata")));
    }

    /** Notes the stored attachment (rest-setup.php {@code attachment}) of a 201 upload, or the refusal. */
    void noteAttachment(String label, Exchange uploaded) throws Exception {
        if (uploaded.status() != 201) {
            ObjectNode refused = args();
            refused.put("status", uploaded.status());
            refused.put("body", uploaded.text());
            VerificationFacts.note(item, "upload " + label, refused);
            return;
        }
        noteStored("upload " + label, uploaded.json().get("id").asLong());
    }

    void noteStored(String key, long id) throws Exception {
        VerificationFacts.note(
                item,
                key,
                wp.operation("attachment", args().put("site", site + "/").put("id", id)));
    }

    JsonNode noteQuery(String mime) throws Exception {
        ObjectNode input = args().put("site", site + "/");
        input.set("args", args().put("post_mime_type", mime));
        JsonNode answer = wp.operation("query_attachments", input);
        VerificationFacts.note(item, "query_attachments " + mime, answer);
        return answer;
    }

    void noteMeta(String key, long post, String metaKey) throws Exception {
        VerificationFacts.note(
                item,
                key,
                wp.operation(
                        "meta", args().put("site", site + "/").put("post", post).put("key", metaKey)));
    }

    void noteListing() throws Exception {
        VerificationFacts.note(
                item, "uploads_listing", wp.operation("uploads_listing", args().put("site", site + "/")));
    }

    /** One part of a hand-built multipart body. */
    record Part(String name, Optional<String> fileName, Optional<String> type, byte[] content) {}

    static Part filePart(String name, String fileName, String type, byte[] content) {
        return new Part(name, Optional.of(fileName), Optional.of(type), content);
    }

    static Part textPart(String name, String value) {
        return new Part(name, Optional.empty(), Optional.empty(), value.getBytes(StandardCharsets.UTF_8));
    }

    static Part noSizesPart() {
        return textPart("generate_sub_sizes", "false");
    }

    /** A multipart upload with the parts as given (any names), recorded with the parts summarised. */
    Exchange multipart(String label, List<Part> parts) throws Exception {
        String boundary = RestCall.MULTIPART_BOUNDARY;
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        StringBuilder summary = new StringBuilder("multipart " + label + ":");
        for (Part part : parts) {
            StringBuilder head = new StringBuilder("--" + boundary + "\r\nContent-Disposition: form-data; name=\""
                    + RestCall.escapeName(part.name()) + "\"");
            part.fileName()
                    .ifPresent(name -> head.append("; filename=\"")
                            .append(RestCall.escapeName(name))
                            .append('"'));
            head.append("\r\n");
            part.type()
                    .ifPresent(
                            type -> head.append("Content-Type: ").append(type).append("\r\n"));
            head.append("\r\n");
            body.writeBytes(head.toString().getBytes(StandardCharsets.UTF_8));
            body.writeBytes(part.content());
            body.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
            summary.append(" [name=\"").append(part.name()).append('"');
            part.fileName()
                    .ifPresent(
                            name -> summary.append(" filename=\"").append(name).append('"'));
            part.type().ifPresent(type -> summary.append(" type=").append(type));
            summary.append(
                            part.fileName().isPresent()
                                    ? " bytes=" + part.content().length + " sha256=" + sha256(part.content())
                                    : " value=" + new String(part.content(), StandardCharsets.UTF_8))
                    .append(']');
        }
        body.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        RestCall call = RestCall.raw(
                "alice",
                api("wp/v2/media"),
                Optional.of("multipart/form-data; boundary=" + boundary),
                Map.of(),
                body.toByteArray());
        Exchange exchange = client.send(call);
        record(call, summary.toString(), exchange);
        return exchange;
    }

    /** Header lines: one name with its values, in order. */
    static Map<String, List<String>> lines(String name, String... values) {
        return Map.of(name, List.of(values));
    }

    /** A map of the pairs in order. */
    static Map<String, String> fields(String... pairs) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            fields.put(pairs[i], pairs[i + 1]);
        }
        return fields;
    }

    static ArrayNode strings(String... values) {
        ArrayNode array = OracleJson.MAPPER.createArrayNode();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    static JsonNode withoutPassword(JsonNode answer) {
        ObjectNode copy = answer.deepCopy();
        copy.remove("password");
        return copy;
    }

    /** {@code bytes} followed by filler up to {@code size} bytes (after the image's end marker). */
    static byte[] padded(byte[] bytes, int size) {
        byte[] out = new byte[Math.max(size, bytes.length)];
        System.arraycopy(bytes, 0, out, 0, bytes.length);
        for (int i = bytes.length; i < out.length; i++) {
            out[i] = (byte) ('a' + (i % 26));
        }
        return out;
    }

    static ObjectNode args() {
        return OracleJson.MAPPER.createObjectNode();
    }

    static String sha256(byte[] bytes) {
        return MediaFactsDbTest.sha256(bytes);
    }
}
