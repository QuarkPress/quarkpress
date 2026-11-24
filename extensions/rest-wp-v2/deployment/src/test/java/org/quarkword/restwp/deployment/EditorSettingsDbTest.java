package org.quarkword.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.PostId;
import org.quarkword.core.SiteId;
import org.quarkword.core.UserId;

/**
 * The block editor's settings object, {@code GET /quarkword/v1/editor/<type>/<id>}: WordPress's keys in the order the
 * edit screen prints them for a classic theme (the recorded facts of the harness's {@code quarkword} theme), the media
 * keys following the caller, the recorded static theme defaults, the lock reported and never taken, nothing written by a
 * GET, the refusals, the autosave link only for a newer autosave, the super-admin-only CSS capability, and the block
 * definitions of the application's registry. Members of the site: the super admin alice, the site administrator sam,
 * the editor bob (Bob Baker), the contributor dave and the subscriber erin.
 */
class EditorSettingsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(
                    EditorSettingsDbTest.class,
                    TestBlocks.class,
                    TestBlocks.Renderer.class,
                    PostFixtures.class,
                    PostFixtures.Builder.class)
            .overrideConfigKey("quarkword.media.max-upload-size", "1M");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String EDITOR = "/wp-json/quarkword/v1/editor/";

    private static final String LOCKS = "/wp-json/quarkword/v1/locks/";

    private static final String GRAVATAR = "https://secure.gravatar.com/avatar/";

    /** Fact v07: the settings keys as the edit screen prints them for bob on the classic theme, minus the four unserved. */
    private static final List<String> KEYS = List.of(
            "alignWide",
            "allowedBlockTypes",
            "allowedMimeTypes",
            "blockCategories",
            "isRTL",
            "imageDefaultSize",
            "imageDimensions",
            "imageEditing",
            "imageSizes",
            "maxUploadFileSize",
            "__experimentalDashboardLink",
            "__unstableGalleryWithImageBlocks",
            "disableCustomColors",
            "disableCustomFontSizes",
            "disableCustomGradients",
            "disableLayoutStyles",
            "enableCustomLineHeight",
            "enableCustomSpacing",
            "enableCustomUnits",
            "availableTemplates",
            "disablePostFormats",
            "titlePlaceholder",
            "bodyPlaceholder",
            "autosaveInterval",
            "richEditingEnabled",
            "postLock",
            "supportsLayout",
            "supportsTemplateMode",
            "enableCustomFields",
            "__experimentalAdditionalBlockPatterns",
            "__experimentalAdditionalBlockPatternCategories",
            "__experimentalBlockBindingsSupportedAttributes",
            "__experimentalFeatures",
            "colors",
            "gradients",
            "fontSizes",
            "disableCustomSpacingSizes",
            "spacingSizes",
            "__unstableIsBlockBasedTheme",
            "localAutosaveInterval",
            "__experimentalDiscussionSettings",
            "canUpdateBlockBindings",
            "canEditCSS");

    static AgroalDataSource data;
    static SiteId site;
    static String host;
    static long alice;
    static long sam;
    static long bob;
    static long dave;
    static long erin;

    @BeforeAll
    static void members() {
        data = Jdbc.dataSource();
        site = TestData.site(data);
        host = Jdbc.strings(data, "select domain from qw_site where id = ?", site.value())
                .get(0);
        alice = member("alice", "Alice Adams", "alice@example.com", "administrator");
        TestData.superAdmin(data, UserId.of(alice));
        sam = member("sam", "Sam Site", "sam@example.com", "administrator");
        bob = member("bob", "Bob Baker", "bob@example.com", "editor");
        dave = member("dave", "Dave Diaz", "dave@example.com", "contributor");
        erin = member("erin", "Erin Evans", "erin@example.com", "subscriber");
    }

    private static long member(String nicename, String displayName, String email, String role) {
        UserId user = TestData.profile(data, nicename, displayName, email);
        TestData.member(data, site, user, "[\"" + role + "\"]");
        return user.value();
    }

    @Test
    void keysInWordPresssOrder() {
        PostId post = publishedOf(bob);
        Response answer = as(bob).get(EDITOR + "post/" + post.value());
        answer.then().statusCode(200).header("Allow", "GET");
        JsonNode body = json(answer);
        assertThat(names(body)).containsExactly("settings", "preload", "blockDefinitions", "dateSettings");
        // the preload map (its own test): an object
        assertThat(body.get("preload").isObject()).isTrue();
        JsonNode settings = body.get("settings");
        assertThat(names(settings)).containsExactlyElementsOf(KEYS);
        // the values WordPress computes for a classic theme without a theme.json (facts v07, v08)
        assertThat(settings.get("alignWide").asBoolean()).isTrue();
        assertThat(settings.get("allowedBlockTypes").asBoolean()).isTrue();
        assertThat(settings.get("blockCategories").toString())
                .isEqualTo("[{\"slug\":\"text\",\"title\":\"Text\",\"icon\":null},"
                        + "{\"slug\":\"media\",\"title\":\"Media\",\"icon\":null},"
                        + "{\"slug\":\"design\",\"title\":\"Design\",\"icon\":null},"
                        + "{\"slug\":\"widgets\",\"title\":\"Widgets\",\"icon\":null},"
                        + "{\"slug\":\"theme\",\"title\":\"Theme\",\"icon\":null},"
                        + "{\"slug\":\"embed\",\"title\":\"Embeds\",\"icon\":null},"
                        + "{\"slug\":\"reusable\",\"title\":\"Patterns\",\"icon\":null}]");
        assertThat(settings.get("isRTL").asBoolean()).isFalse();
        assertThat(settings.get("imageDefaultSize").asText()).isEqualTo("large");
        assertThat(settings.get("imageDimensions").toString())
                .isEqualTo("{\"thumbnail\":{\"width\":150,\"height\":150,\"crop\":true},"
                        + "\"medium\":{\"width\":300,\"height\":300,\"crop\":false},"
                        + "\"large\":{\"width\":1024,\"height\":1024,\"crop\":false}}");
        assertThat(settings.get("imageEditing").asBoolean()).isTrue();
        assertThat(settings.get("imageSizes").toString())
                .isEqualTo("[{\"slug\":\"thumbnail\",\"name\":\"Thumbnail\"},{\"slug\":\"medium\",\"name\":\"Medium\"},"
                        + "{\"slug\":\"large\",\"name\":\"Large\"},{\"slug\":\"full\",\"name\":\"Full Size\"}]");
        assertThat(settings.get("__experimentalDashboardLink").asText()).isEqualTo("http://" + host + "/wp-admin/");
        assertThat(settings.get("__unstableGalleryWithImageBlocks").asBoolean()).isTrue();
        assertThat(settings.get("availableTemplates").toString()).isEqualTo("[]");
        assertThat(settings.get("disablePostFormats").asBoolean()).isTrue();
        assertThat(settings.get("titlePlaceholder").asText()).isEqualTo("Add title");
        assertThat(settings.get("bodyPlaceholder").asText()).isEqualTo("Type / to choose a block");
        assertThat(settings.get("autosaveInterval").asInt()).isEqualTo(60);
        assertThat(settings.get("richEditingEnabled").asBoolean()).isTrue();
        assertThat(settings.get("supportsLayout").asBoolean()).isFalse();
        assertThat(settings.get("supportsTemplateMode").asBoolean()).isFalse();
        // the post type supports custom fields, so the key is present and false
        assertThat(settings.get("enableCustomFields").asBoolean()).isFalse();
        assertThat(settings.get("__experimentalAdditionalBlockPatterns").toString())
                .isEqualTo("[]");
        assertThat(settings.get("__experimentalAdditionalBlockPatternCategories")
                        .toString())
                .isEqualTo("[]");
        assertThat(settings.get("__experimentalBlockBindingsSupportedAttributes")
                        .toString())
                .isEqualTo("{}");
        assertThat(settings.get("__unstableIsBlockBasedTheme").asBoolean()).isFalse();
        assertThat(settings.get("localAutosaveInterval").asInt()).isEqualTo(15);
        // fact v08: the eight discussion options as strings, the avatar the forced 96-pixel default
        assertThat(settings.get("__experimentalDiscussionSettings").toString())
                .isEqualTo("{\"commentOrder\":\"asc\",\"commentsPerPage\":\"50\",\"defaultCommentsPage\":\"newest\","
                        + "\"pageComments\":\"0\",\"threadComments\":\"1\",\"threadCommentsDepth\":\"5\","
                        + "\"defaultCommentStatus\":\"open\","
                        + "\"avatarURL\":\"https://secure.gravatar.com/avatar/?s=96&d=mm&f=y&r=g\"}");
        assertThat(settings.get("canUpdateBlockBindings").asBoolean()).isTrue();
        // a type without custom-fields support (an attachment) keeps the same order without that key
        PostId attachment = PostFixtures.builder(site)
                .type("attachment")
                .status("inherit")
                .author(bob)
                .insert(data);
        Response attached = as(bob).get(EDITOR + "attachment/" + attachment.value());
        attached.then().statusCode(200);
        List<String> expected = new ArrayList<>(KEYS);
        expected.remove("enableCustomFields");
        assertThat(names(json(attached).get("settings"))).containsExactlyElementsOf(expected);
    }

    @Test
    void mediaKeysFollowTheCaller() {
        PostId post = publishedOf(bob);
        // the editor uploads files: the configured maximum (1M, under the network's 1500 KB per-file limit)
        JsonNode bobs = settings(bob, post);
        assertThat(bobs.get("maxUploadFileSize").asLong()).isEqualTo(1_048_576L);
        assertThat(bobs.get("allowedMimeTypes").get("jpg|jpeg|jpe").asText()).isEqualTo("image/jpeg");
        // the contributor may not upload: 0 (fact v09); his own draft
        PostId draft = draftOf(dave);
        JsonNode daves = settings(dave, draft);
        assertThat(daves.get("maxUploadFileSize").asLong()).isZero();
        assertThat(daves.get("allowedMimeTypes").has("jpg|jpeg|jpe")).isTrue();
        // on a network only a super admin holds unfiltered_html, so only alice may upload HTML (fact v09)
        JsonNode alices = settings(alice, post);
        assertThat(alices.get("allowedMimeTypes").has("htm|html")).isTrue();
        assertThat(alices.get("allowedMimeTypes").has("js")).isTrue();
        JsonNode sams = settings(sam, post);
        assertThat(sams.get("allowedMimeTypes").has("htm|html")).isFalse();
        assertThat(sams.get("allowedMimeTypes").has("js")).isFalse();
        assertThat(bobs.get("allowedMimeTypes").has("htm|html")).isFalse();
    }

    @Test
    void mediaFeatureOffAnswersAsWithoutTheMediaModule() {
        PostId post = publishedOf(bob);
        TestData.siteOption(data, site, "qw_features", "{\"media\": false}");
        try {
            Response answer = as(bob).get(EDITOR + "post/" + post.value());
            answer.then().statusCode(200);
            JsonNode body = json(answer);
            JsonNode settings = body.get("settings");
            // nothing may be uploaded and no sub-size is registered, the editor's sizes are still named
            assertThat(settings.get("allowedMimeTypes").toString()).isEqualTo("{}");
            assertThat(settings.get("maxUploadFileSize").asLong()).isZero();
            assertThat(settings.has("imageDimensions")).isFalse();
            assertThat(settings.get("imageSizes").findValuesAsText("slug"))
                    .containsExactly("thumbnail", "medium", "large", "full");
            // the media routes answer no route on that site, but OPTIONS still describes them (Limits), so the
            // preload keeps WordPress's entry
            assertThat(names(body.at("/preload/OPTIONS"))).contains("/wp/v2/media");
        } finally {
            Jdbc.update(data, "delete from qw_option where site_id = ? and name = ?", site.value(), "qw_features");
        }
        JsonNode on = settings(bob, post);
        assertThat(on.get("allowedMimeTypes").has("jpg|jpeg|jpe")).isTrue();
        assertThat(on.has("imageDimensions")).isTrue();
    }

    @Test
    void staticDefaultsAreTheRecordedOnes() {
        JsonNode settings = settings(bob, publishedOf(bob));
        // fact v07: core's theme.json defaults for a classic theme
        assertThat(settings.get("disableCustomColors").asBoolean()).isFalse();
        assertThat(settings.get("disableCustomFontSizes").asBoolean()).isFalse();
        assertThat(settings.get("disableCustomGradients").asBoolean()).isFalse();
        assertThat(settings.get("disableLayoutStyles").asBoolean()).isFalse();
        assertThat(settings.get("enableCustomLineHeight").asBoolean()).isFalse();
        assertThat(settings.get("enableCustomSpacing").asBoolean()).isFalse();
        // false for a classic theme without a theme.json (the six units come only with one)
        assertThat(settings.get("enableCustomUnits").asBoolean()).isFalse();
        assertThat(settings.get("disableCustomSpacingSizes").asBoolean()).isFalse();
        assertThat(settings.get("colors").toString())
                .isEqualTo("[{\"name\":\"Black\",\"slug\":\"black\",\"color\":\"#000000\"},"
                        + "{\"name\":\"Cyan bluish gray\",\"slug\":\"cyan-bluish-gray\",\"color\":\"#abb8c3\"},"
                        + "{\"name\":\"White\",\"slug\":\"white\",\"color\":\"#ffffff\"},"
                        + "{\"name\":\"Pale pink\",\"slug\":\"pale-pink\",\"color\":\"#f78da7\"},"
                        + "{\"name\":\"Vivid red\",\"slug\":\"vivid-red\",\"color\":\"#cf2e2e\"},"
                        + "{\"name\":\"Luminous vivid orange\",\"slug\":\"luminous-vivid-orange\",\"color\":\"#ff6900\"},"
                        + "{\"name\":\"Luminous vivid amber\",\"slug\":\"luminous-vivid-amber\",\"color\":\"#fcb900\"},"
                        + "{\"name\":\"Light green cyan\",\"slug\":\"light-green-cyan\",\"color\":\"#7bdcb5\"},"
                        + "{\"name\":\"Vivid green cyan\",\"slug\":\"vivid-green-cyan\",\"color\":\"#00d084\"},"
                        + "{\"name\":\"Pale cyan blue\",\"slug\":\"pale-cyan-blue\",\"color\":\"#8ed1fc\"},"
                        + "{\"name\":\"Vivid cyan blue\",\"slug\":\"vivid-cyan-blue\",\"color\":\"#0693e3\"},"
                        + "{\"name\":\"Vivid purple\",\"slug\":\"vivid-purple\",\"color\":\"#9b51e0\"}]");
        assertThat(values(settings.get("gradients"), "slug"))
                .containsExactly(
                        "vivid-cyan-blue-to-vivid-purple",
                        "light-green-cyan-to-vivid-green-cyan",
                        "luminous-vivid-amber-to-luminous-vivid-orange",
                        "luminous-vivid-orange-to-vivid-red",
                        "very-light-gray-to-cyan-bluish-gray",
                        "cool-to-warm-spectrum",
                        "blush-light-purple",
                        "blush-bordeaux",
                        "luminous-dusk",
                        "pale-ocean",
                        "electric-grass",
                        "midnight");
        assertThat(settings.get("gradients").get(0).get("gradient").asText())
                .isEqualTo("linear-gradient(135deg,rgb(6,147,227) 0%,rgb(155,81,224) 100%)");
        assertThat(names(settings.get("gradients").get(0))).containsExactly("name", "gradient", "slug");
        assertThat(settings.get("fontSizes").toString())
                .isEqualTo("[{\"name\":\"Small\",\"slug\":\"small\",\"size\":\"13px\"},"
                        + "{\"name\":\"Medium\",\"slug\":\"medium\",\"size\":\"20px\"},"
                        + "{\"name\":\"Large\",\"slug\":\"large\",\"size\":\"36px\"},"
                        + "{\"name\":\"Extra Large\",\"slug\":\"x-large\",\"size\":\"42px\"}]");
        assertThat(settings.get("spacingSizes").toString())
                .isEqualTo("[{\"name\":\"2X-Small\",\"slug\":\"20\",\"size\":\"0.44rem\"},"
                        + "{\"name\":\"X-Small\",\"slug\":\"30\",\"size\":\"0.67rem\"},"
                        + "{\"name\":\"Small\",\"slug\":\"40\",\"size\":\"1rem\"},"
                        + "{\"name\":\"Medium\",\"slug\":\"50\",\"size\":\"1.5rem\"},"
                        + "{\"name\":\"Large\",\"slug\":\"60\",\"size\":\"2.25rem\"},"
                        + "{\"name\":\"X-Large\",\"slug\":\"70\",\"size\":\"3.38rem\"},"
                        + "{\"name\":\"2X-Large\",\"slug\":\"80\",\"size\":\"5.06rem\"}]");
        JsonNode features = settings.get("__experimentalFeatures");
        assertThat(names(features))
                .containsExactly(
                        "appearanceTools",
                        "useRootPaddingAwareAlignments",
                        "border",
                        "background",
                        "color",
                        "dimensions",
                        "shadow",
                        "spacing",
                        "typography",
                        "blocks");
        // the keys WordPress copies out of the global settings are gone from the features
        assertThat(features.at("/color/custom").isMissingNode()).isTrue();
        assertThat(features.at("/spacing/units").isMissingNode()).isTrue();
        assertThat(features.at("/typography/lineHeight").isMissingNode()).isTrue();
        assertThat(features.at("/color/palette/default").size()).isEqualTo(12);
        assertThat(features.at("/spacing/spacingScale/default/increment").asDouble())
                .isEqualTo(1.5);
        assertThat(features.at("/spacing/blockGap").isNull()).isTrue();
    }

    @Test
    void theLockIsReportedNeverTaken() {
        PostId post = publishedOf(bob);
        assertThat(settings(bob, post).get("postLock").toString()).isEqualTo("{\"isLocked\":false}");
        assertThat(storedLock(post)).isNull();
        // bob takes the lock through the locks route; the editor's GET reports it to alice, not to bob
        String lock = as(bob).post(LOCKS + post.value())
                .then()
                .statusCode(200)
                .extract()
                .path("activePostLock");
        assertThat(storedLock(post)).isEqualTo(lock);
        JsonNode alices = settings(alice, post).get("postLock");
        assertThat(alices.toString())
                .isEqualTo("{\"isLocked\":true,\"user\":{\"name\":\"Bob Baker\",\"avatar\":\""
                        + avatar("bob@example.com", 128) + "\"}}");
        assertThat(settings(bob, post).get("postLock").toString()).isEqualTo("{\"isLocked\":false}");
        // neither GET touched the stored value
        assertThat(storedLock(post)).isEqualTo(lock);
    }

    @Test
    void aGetChangesNothing() {
        PostId post = PostFixtures.builder(site)
                .author(bob)
                .title("Unchanged")
                .modifiedAt("2026-10-18T10:00:00Z")
                .insert(data);
        // an older autosave of bob's, which WordPress's edit screen would delete
        long autosave = autosaveRow(post, bob, "older words", "2026-10-18T09:00:00Z");
        List<String> fresh = state(post);
        List<String> optionsBefore = optionNames();
        assertThat(optionsBefore).doesNotContain("quarkword_preview_secret");
        // the autosaves route, which the preload map dispatches, issues a preview token per autosave and so creates
        // the site's preview-token secret the first time a site needs one (the posts module's one-time initialisation,
        // WordPress's install-time salts): the first editor GET on a fresh site adds exactly that option row
        settings(bob, post);
        List<String> optionsAfter = optionNames();
        assertThat(optionsAfter).hasSize(optionsBefore.size() + 1).containsAll(optionsBefore);
        assertThat(optionsAfter).contains("quarkword_preview_secret");
        List<String> before = state(post);
        List<String> expectedBefore = new ArrayList<>(fresh);
        expectedBefore.set(3, "options " + optionsAfter.size());
        assertThat(before).isEqualTo(expectedBefore);
        // from then on nothing changes
        settings(bob, post);
        settings(alice, post);
        assertThat(state(post)).isEqualTo(before);
        assertThat(optionNames()).isEqualTo(optionsAfter);
        assertThat(Jdbc.count(data, "select count(*) from qw_revision where id = ?", autosave))
                .isEqualTo(1);
    }

    @Test
    void onlyEditorsGetSettings() {
        PostId draft = draftOf(bob);
        as(dave).get(EDITOR + "post/" + draft.value())
                .then()
                .statusCode(403)
                .body(equalTo("{\"code\":\"rest_cannot_edit\",\"message\":\"Sorry, you are not allowed to edit this"
                        + " post.\",\"data\":{\"status\":403}}"));
        as(erin).get(EDITOR + "post/" + draft.value()).then().statusCode(403).body("code", equalTo("rest_cannot_edit"));
        as(null).get(EDITOR + "post/" + draft.value())
                .then()
                .statusCode(401)
                .body(equalTo("{\"code\":\"rest_cannot_edit\",\"message\":\"Sorry, you are not allowed to edit this"
                        + " post.\",\"data\":{\"status\":401}}"));
        // not a post of that type on this site: another site's post, an attachment under /post/, an unknown type, 0
        PostId elsewhere =
                PostFixtures.builder(TestData.site(data)).author(alice).insert(data);
        PostId attachment = PostFixtures.builder(site)
                .type("attachment")
                .status("inherit")
                .author(bob)
                .insert(data);
        String invalid =
                "{\"code\":\"rest_post_invalid_id\",\"message\":\"Invalid post ID.\",\"data\":{\"status\":404}}";
        for (String path : List.of(
                "post/" + elsewhere.value(),
                "post/" + attachment.value(),
                "nope/" + draft.value(),
                "page/" + draft.value(),
                "post/0",
                "post/999999999")) {
            as(alice).get(EDITOR + path).then().statusCode(404).body(equalTo(invalid));
        }
        // the id check comes before the capability check, so a stranger learns nothing more than "invalid"
        as(null).get(EDITOR + "post/999999999").then().statusCode(404).body(equalTo(invalid));
        // dave may edit his own draft
        as(dave).get(EDITOR + "post/" + draftOf(dave).value()).then().statusCode(200);
        // only GET is served
        as(bob).post(EDITOR + "post/" + draft.value()).then().statusCode(404).body("code", equalTo("rest_no_route"));
    }

    @Test
    void autosaveLinkOnlyWhenNewer() {
        // bob's autosave is newer than the post: the link to WordPress's revision screen under the dashboard URL
        PostId post = PostFixtures.builder(site)
                .author(bob)
                .title("Autosaved")
                .modifiedAt("2026-10-18T10:00:00Z")
                .insert(data);
        long newer = autosaveRow(post, bob, "newer words", "2026-10-18T11:00:00Z");
        JsonNode settings = settings(bob, post);
        assertThat(settings.get("autosave").toString())
                .isEqualTo("{\"editLink\":\"http://" + host + "/wp-admin/revision.php?revision=" + newer + "\"}");
        List<String> keys = names(settings);
        assertThat(keys.get(keys.indexOf("__experimentalAdditionalBlockPatternCategories") + 1))
                .isEqualTo("autosave");
        assertThat(keys.get(keys.indexOf("autosave") + 1)).isEqualTo("__experimentalBlockBindingsSupportedAttributes");
        // WordPress checks the newest autosave of any user: alice's screen links bob's too
        assertThat(settings(alice, post).get("autosave").get("editLink").asText())
                .isEqualTo("http://" + host + "/wp-admin/revision.php?revision=" + newer);
        // an older autosave: no key, and the autosave is still stored
        PostId stale = PostFixtures.builder(site)
                .author(bob)
                .title("Stale")
                .modifiedAt("2026-10-18T10:00:00Z")
                .insert(data);
        long older = autosaveRow(stale, bob, "older words", "2026-10-18T09:00:00Z");
        assertThat(settings(bob, stale).has("autosave")).isFalse();
        assertThat(Jdbc.count(data, "select count(*) from qw_revision where id = ?", older))
                .isEqualTo(1);
    }

    @Test
    void canEditCssAndBindings() {
        PostId post = publishedOf(bob);
        // on a network only super admins hold edit_css (fact v09); everyone who may edit the post may bind blocks
        JsonNode alices = settings(alice, post);
        assertThat(alices.get("canEditCSS").asBoolean()).isTrue();
        assertThat(alices.get("canUpdateBlockBindings").asBoolean()).isTrue();
        JsonNode bobs = settings(bob, post);
        assertThat(bobs.get("canEditCSS").asBoolean()).isFalse();
        assertThat(bobs.get("canUpdateBlockBindings").asBoolean()).isTrue();
        assertThat(settings(sam, post).get("canEditCSS").asBoolean()).isFalse();
    }

    @Test
    void blockDefinitionsFromTheRegistry() {
        JsonNode definitions =
                json(as(bob).get(EDITOR + "post/" + publishedOf(bob).value())).get("blockDefinitions");
        assertThat(names(definitions)).containsExactly("acme/notice", "acme/dyn", "acme/full", "other/plain");
        // a title-only type: the version, the title, the always-set fields and the attributes its supports add
        JsonNode notice = definitions.get("acme/notice");
        assertThat(names(notice))
                .containsExactly(
                        "apiVersion",
                        "title",
                        "description",
                        "attributes",
                        "usesContext",
                        "blockHooks",
                        "selectors",
                        "styles",
                        "keywords",
                        "variations");
        assertThat(notice.get("apiVersion").asInt()).isEqualTo(1);
        assertThat(notice.get("title").asText()).isEqualTo("Notice");
        // the fields WordPress's block type always holds print empty, as PHP's isset sees them
        assertThat(notice.get("description").asText()).isEmpty();
        for (String always : List.of("usesContext", "blockHooks", "selectors", "styles", "keywords", "variations")) {
            assertThat(notice.get(always).toString()).as(always).isEqualTo("[]");
        }
        assertThat(names(notice.get("attributes"))).containsExactly("lock", "metadata", "className", "style");
        // every property set, in WordPress's order
        JsonNode full = definitions.get("acme/full");
        assertThat(names(full))
                .containsExactly(
                        "apiVersion",
                        "title",
                        "description",
                        "icon",
                        "attributes",
                        "providesContext",
                        "usesContext",
                        "blockHooks",
                        "selectors",
                        "supports",
                        "category",
                        "styles",
                        "textdomain",
                        "parent",
                        "ancestor",
                        "keywords",
                        "example",
                        "variations",
                        "allowedBlocks");
        assertThat(full.get("apiVersion").asInt()).isEqualTo(3);
        assertThat(full.get("icon").asText()).isEqualTo("smiley");
        assertThat(full.get("category").asText()).isEqualTo("widgets");
        assertThat(full.get("parent").toString()).isEqualTo("[\"core/group\"]");
        assertThat(full.get("providesContext").toString()).isEqualTo("{\"acme/id\":\"content\"}");
        assertThat(full.get("supports").toString()).isEqualTo("{\"anchor\":true,\"html\":false}");
        assertThat(names(full.get("attributes"))).startsWith("content", "opts", "lock", "metadata");
        // an empty object inside a definition prints as WordPress prints a PHP array
        assertThat(full.at("/attributes/opts/default").toString()).isEqualTo("[]");
    }

    // ---- helpers

    /** A request to the test site as the user with this id, or anonymous when null. */
    private static RequestSpecification as(Long user) {
        return Http.at(host, user == null ? null : "test-" + user);
    }

    private static JsonNode settings(long user, PostId post) {
        Response answer = as(user).get(EDITOR + "post/" + post.value());
        answer.then().statusCode(200);
        return json(answer).get("settings");
    }

    private static PostId publishedOf(long author) {
        return PostFixtures.builder(site)
                .author(author)
                .title("Edited " + UUID.randomUUID())
                .insert(data);
    }

    private static PostId draftOf(long author) {
        return PostFixtures.builder(site)
                .status("draft")
                .author(author)
                .title("Drafted " + UUID.randomUUID())
                .publishedAt(null)
                .insert(data);
    }

    /** An autosave row of {@code author} on the post, written past the services; answers its id. */
    private static long autosaveRow(PostId post, long author, String content, String createdAt) {
        String marker = "auto-" + UUID.randomUUID();
        Jdbc.update(
                data,
                "insert into qw_revision (site_id, post_id, author_id, title, content, excerpt, meta, autosave,"
                        + " autosave_owner, created_at) values (?, ?, ?, ?, ?, ?, " + TestDb.json() + ", ?, ?, "
                        + PostFixtures.instant() + ")",
                site.value(),
                post.value(),
                author,
                "",
                content,
                marker,
                "{}",
                true,
                author,
                PostFixtures.instantText(createdAt));
        long id = Jdbc.count(data, "select id from qw_revision where excerpt = ?", marker);
        Jdbc.update(data, "update qw_revision set excerpt = '' where id = ?", id);
        return id;
    }

    /** What a GET must leave alone: the post row's change markers and meta, the site's options, the revisions. */
    private static List<String> state(PostId post) {
        List<String> state = new ArrayList<>();
        state.add("modified "
                + Jdbc.strings(data, "select concat(modified_at, '') from qw_post where id = ?", post.value()));
        state.add("version " + Jdbc.count(data, "select version from qw_post where id = ?", post.value()));
        state.add("meta " + Jdbc.strings(data, "select meta from qw_post where id = ?", post.value()));
        state.add("options " + Jdbc.count(data, "select count(*) from qw_option where site_id = ?", site.value()));
        state.add("revisions " + Jdbc.count(data, "select count(*) from qw_revision where post_id = ?", post.value()));
        state.add("locks " + storedLock(post));
        return state;
    }

    /** The names of the site's options, in name order. */
    private static List<String> optionNames() {
        return Jdbc.strings(data, "select name from qw_option where site_id = ? order by name", site.value());
    }

    /** The stored {@code _edit_lock} value, read past the services; null when the post has none. */
    private static String storedLock(PostId post) {
        String meta = Jdbc.strings(data, "select meta from qw_post where id = ?", post.value())
                .get(0);
        JsonNode lock = json(meta).path("_edit_lock").path(0);
        return lock.isMissingNode() ? null : lock.textValue();
    }

    /** WordPress's avatar URL on a site with the default image and rating options unset. */
    private static String avatar(String email, int size) {
        return GRAVATAR + sha256(email) + "?s=" + size + "&d=mm&r=g";
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> names(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> values(JsonNode list, String field) {
        List<String> values = new ArrayList<>();
        list.forEach(item -> values.add(item.get(field).asText()));
        return values;
    }

    private static JsonNode json(Response response) {
        return json(response.asString());
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
