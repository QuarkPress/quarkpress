package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.quarkpress.restwp.deployment.TestData.MAIN;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.runtime.SiteDefaults;

/**
 * {@code /wp/v2/settings} on a network, for the main site (whose options the migrations wrote) and a second site
 * (whose options are a new site's): only a caller with {@code manage_options} on the site reads or writes them, the
 * values are WordPress's, and writes go through WordPress's per-option sanitizing. Each test starts from the main
 * site's default options.
 */
class SettingsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.multisite(SettingsDbTest.class);

    static final ObjectMapper PLAIN = new ObjectMapper();

    static final String SETTINGS = "/wp-json/wp/v2/settings";

    long administrator;
    long editor;
    long otherAdministrator;
    SiteId other;
    String otherHost;

    @BeforeEach
    void site() {
        AgroalDataSource data = Jdbc.dataSource();
        // the main site's options as the migrations wrote them (default_category is never written by these tests)
        for (Map.Entry<String, JsonNode> option :
                SiteDefaults.forNewSite("QuarkPress").entrySet()) {
            String name = option.getKey();
            if (!name.equals("WPLANG") && !name.equals("default_category") && !name.startsWith("qp_")) {
                Jdbc.option(MAIN, name, option.getValue().toString());
            }
        }
        for (String name : List.of("WPLANG", "site_logo", "siteurl", "admin_email")) {
            Jdbc.deleteOption(MAIN, name);
        }
        administrator = TestData.memberOfMain(data, "administrator").value();
        editor = TestData.memberOfMain(data, "editor").value();
        other = TestData.site(data);
        otherHost = Jdbc.strings(data, "select domain from qp_site where id = ?", other.value())
                .get(0);
        otherAdministrator = TestData.profile(data, "other-admin").value();
        TestData.member(data, other, UserId.of(otherAdministrator), "[\"administrator\"]");
    }

    @Test
    void onlyManageOptionsMayReadOrWrite() {
        Http.as("test-" + editor)
                .get(SETTINGS)
                .then()
                .statusCode(403)
                .header("Allow", nullValue())
                .body(
                        equalTo(
                                "{\"code\":\"rest_forbidden\",\"message\":\"Sorry, you are not allowed to do that.\",\"data\":{\"status\":403}}"));
        Http.as(null)
                .get(SETTINGS)
                .then()
                .statusCode(401)
                .body(
                        equalTo(
                                "{\"code\":\"rest_forbidden\",\"message\":\"Sorry, you are not allowed to do that.\",\"data\":{\"status\":401}}"));
        for (String method : List.of("POST", "PUT", "PATCH")) {
            Http.as("test-" + editor)
                    .contentType("application/json")
                    .body("{\"title\":\"Editor's\"}")
                    .request(method, SETTINGS)
                    .then()
                    .statusCode(403)
                    .body("code", equalTo("rest_forbidden"))
                    .body("message", equalTo("Sorry, you are not allowed to do that."));
            Http.as(null)
                    .contentType("application/json")
                    .body("{\"title\":\"Anonymous\"}")
                    .request(method, SETTINGS)
                    .then()
                    .statusCode(401)
                    .body("code", equalTo("rest_forbidden"));
        }
        assertThat(title()).isEqualTo("QuarkPress");
        Http.as("test-" + administrator).get(SETTINGS).then().statusCode(200).header("Allow", "GET, POST, PUT, PATCH");
    }

    @Test
    void anAdministratorOfAnotherSiteMayNeitherReadNorWrite() {
        // the other site's administrator on the main site, and the main site's administrator on the other site
        Http.as("test-" + otherAdministrator)
                .get(SETTINGS)
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_forbidden"));
        Http.as("test-" + otherAdministrator)
                .contentType("application/json")
                .body("{\"title\":\"Taken over\"}")
                .post(SETTINGS)
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_forbidden"));
        Http.at(otherHost, "test-" + administrator)
                .get(SETTINGS)
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_forbidden"));
        Http.at(otherHost, "test-" + administrator)
                .contentType("application/json")
                .body("{\"title\":\"Taken over\"}")
                .post(SETTINGS)
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_forbidden"));
        assertThat(title()).isEqualTo("QuarkPress");
        // each site's own administrator reads and writes only that site's settings
        JsonNode otherSettings = json(Http.at(otherHost, "test-" + otherAdministrator)
                .contentType("application/json")
                .body("{\"title\":\"Other\"}")
                .post(SETTINGS)
                .then()
                .statusCode(200)
                .extract()
                .asString());
        assertThat(otherSettings.get("title")).isEqualTo(TextNode.valueOf("Other"));
        // a network's later site has WPLANG stored empty, which WordPress reads as an empty language
        assertThat(otherSettings.get("language")).isEqualTo(TextNode.valueOf(""));
        assertThat(title()).isEqualTo("QuarkPress");
        assertThat(Jdbc.optionJson(other, "blogname")).isEqualTo("\"Other\"");
    }

    @Test
    void freshSiteValuesInRegistrationOrder() {
        JsonNode settings = json(Http.as("test-" + administrator).get(SETTINGS).asString());
        assertThat(fieldNames(settings))
                .containsExactly(
                        "title",
                        "description",
                        "timezone",
                        "date_format",
                        "time_format",
                        "start_of_week",
                        "language",
                        "use_smilies",
                        "default_category",
                        "default_post_format",
                        "posts_per_page",
                        "show_on_front",
                        "page_on_front",
                        "page_for_posts",
                        "default_ping_status",
                        "default_comment_status",
                        "site_logo",
                        "site_icon");
        // type-strict: json(...) parses with a plain ObjectMapper, so small integers are IntNode; null, "1" or 1.0 fail
        assertThat(settings.get("title")).isEqualTo(TextNode.valueOf("QuarkPress"));
        assertThat(settings.get("description")).isEqualTo(TextNode.valueOf(""));
        assertThat(settings.get("date_format")).isEqualTo(TextNode.valueOf("F j, Y"));
        assertThat(settings.get("time_format")).isEqualTo(TextNode.valueOf("g:i a"));
        assertThat(settings.get("start_of_week")).isEqualTo(IntNode.valueOf(1));
        assertThat(settings.get("posts_per_page")).isEqualTo(IntNode.valueOf(10));
        assertThat(settings.get("use_smilies")).isEqualTo(BooleanNode.TRUE);
        assertThat(settings.get("default_post_format")).isEqualTo(TextNode.valueOf("0"));
        assertThat(settings.get("show_on_front")).isEqualTo(TextNode.valueOf("posts"));
        assertThat(settings.get("page_on_front")).isEqualTo(IntNode.valueOf(0));
        assertThat(settings.get("page_for_posts")).isEqualTo(IntNode.valueOf(0));
        assertThat(settings.get("site_logo").isNull()).isTrue();
        assertThat(settings.get("site_icon")).isEqualTo(IntNode.valueOf(0));
        // the site's Uncategorized term (the main site's category with slug "uncategorized"), never 0
        assertThat(settings.get("default_category"))
                .isEqualTo(IntNode.valueOf((int) Jdbc.termId(MAIN, "category", "uncategorized")));
        // REST verification list item 28 for a fresh site: language "en_US", timezone "", default_ping_status /
        // default_comment_status "open"
        assertThat(settings.get("language")).isEqualTo(TextNode.valueOf("en_US"));
        assertThat(settings.get("timezone")).isEqualTo(TextNode.valueOf(""));
        assertThat(settings.get("default_ping_status")).isEqualTo(TextNode.valueOf("open"));
        assertThat(settings.get("default_comment_status")).isEqualTo(TextNode.valueOf("open"));
        // REST verification list items 19 and 28: the index's site_logo is 0 while the setting is null, both as
        // WordPress answers
        assertThat(json(Http.as(null).get("/wp-json/").asString()).get("site_logo"))
                .isEqualTo(IntNode.valueOf(0));
    }

    @Test
    void optionsDescribesTheSettingsAsWordPressDoes() {
        // WordPress's OPTIONS body of the route on its network, byte for byte
        Http.as("test-" + administrator)
                .options(SETTINGS)
                .then()
                .statusCode(200)
                .header("Allow", "GET, POST, PUT, PATCH")
                .body(equalTo(OPTIONS_BODY));
    }

    @Test
    void invalidValuesAbortTheWholeRequest() {
        Http.as("test-" + administrator)
                .contentType("application/json")
                .body("{\"title\":\"T\",\"posts_per_page\":\"abc\"}")
                .post(SETTINGS)
                .then()
                .statusCode(400)
                .header("Allow", "GET, POST, PUT, PATCH")
                .body("code", equalTo("rest_invalid_param"))
                .body("message", equalTo("Invalid parameter(s): posts_per_page"))
                .body("data.params.posts_per_page", equalTo("posts_per_page is not of type integer."))
                .body("data.details.posts_per_page.code", equalTo("rest_invalid_type"))
                .body("data.details.posts_per_page.data.param", equalTo("posts_per_page"));
        assertThat(title()).isNotEqualTo("T");
        Http.as("test-" + administrator)
                .contentType("application/json")
                .body("{\"default_comment_status\":\"maybe\"}")
                .put(SETTINGS)
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_invalid_param"))
                .body("message", equalTo("Invalid parameter(s): default_comment_status"))
                .body(
                        "data.params.default_comment_status",
                        equalTo("default_comment_status is not one of open and closed."))
                .body("data.details.default_comment_status.code", equalTo("rest_not_in_enum"))
                .body("data.details.default_comment_status.data", nullValue());
        // REST verification list item 28: "" is refused by the enum before any sanitization could turn it into "closed"
        Http.as("test-" + administrator)
                .contentType("application/json")
                .body("{\"default_ping_status\":\"\"}")
                .post(SETTINGS)
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_invalid_param"))
                .body("message", equalTo("Invalid parameter(s): default_ping_status"))
                .body("data.params.default_ping_status", equalTo("default_ping_status is not one of open and closed."))
                .body("data.details.default_ping_status.code", equalTo("rest_not_in_enum"));
        assertThat(setting("default_ping_status")).isEqualTo(TextNode.valueOf("open"));
    }

    @Test
    void titleIsStoredEscapedAndEncodedLikePhp() {
        String body = Http.as("test-" + administrator)
                .contentType("application/json")
                .body("{\"title\":\"A & B <i>/\"}")
                .post(SETTINGS)
                .asString();
        assertThat(body).contains("\"title\":\"A &amp; B &lt;i&gt;\\/\"");
        assertThat(Jdbc.optionJson(MAIN, "blogname")).isEqualTo("\"A &amp; B &lt;i&gt;/\"");
        // Review Focus 4: non-ASCII and HTML-special text, stored escaped and encoded as PHP encodes it
        String special =
                "Caf" + new String(Character.toChars(0xE9)) + "/Bar <b> & " + new String(Character.toChars(0x1F600));
        String encoded = Http.as("test-" + administrator)
                .contentType("application/json; charset=UTF-8")
                .body("{\"title\":\"" + special + "\"}")
                .post(SETTINGS)
                .asString();
        assertThat(encoded).contains("\"title\":\"Caf\\u00e9\\/Bar &lt;b&gt; &amp; \\ud83d\\ude00\"");
    }

    @Test
    void storedInvalidValuesReadAsNullAndRefuseANullReset() {
        // REST verification list item 28's case: use_smilies stored as "abc"; the earlier title in the same request is
        // written
        Jdbc.option(MAIN, "use_smilies", "\"abc\"");
        assertThat(setting("use_smilies").isNull()).isTrue();
        Http.as("test-" + administrator)
                .contentType("application/json")
                .body("{\"title\":\"Before\",\"use_smilies\":null}")
                .post(SETTINGS)
                .then()
                .statusCode(500)
                .header("Allow", "GET, POST, PUT, PATCH")
                .body(equalTo("{\"code\":\"rest_invalid_stored_value\","
                        + "\"message\":\"The use_smilies property has an invalid stored value, and cannot be updated to null.\","
                        + "\"data\":{\"status\":500}}"));
        assertThat(title()).isEqualTo("Before");
        assertThat(Jdbc.optionJson(MAIN, "use_smilies")).isEqualTo("\"abc\"");
        // an option that does not exist and has no default reads as PHP's false: not a string, so also refused
        post("{\"title\":null}");
        assertThat(Jdbc.optionJson(MAIN, "blogname")).isNull();
        Http.as("test-" + administrator)
                .contentType("application/json")
                .body("{\"title\":null}")
                .post(SETTINGS)
                .then()
                .statusCode(500)
                .body("code", equalTo("rest_invalid_stored_value"))
                .body(
                        "message",
                        equalTo("The title property has an invalid stored value, and cannot be updated to null."));
    }

    @Test
    void nullResetsQueryParamsCountAndSanitizationIsSilent() {
        // null deletes the option: the setting falls back to its default, or to null without one
        assertThat(post("{\"posts_per_page\":7}").get("posts_per_page")).isEqualTo(IntNode.valueOf(7));
        assertThat(post("{\"posts_per_page\":null}").get("posts_per_page")).isEqualTo(IntNode.valueOf(10));
        assertThat(Jdbc.optionJson(MAIN, "posts_per_page")).isNull();
        // a second reset checks the option itself, which no longer exists (PHP's false): refused, the default stays
        Http.as("test-" + administrator)
                .contentType("application/json")
                .body("{\"posts_per_page\":null}")
                .post(SETTINGS)
                .then()
                .statusCode(500)
                .body(equalTo("{\"code\":\"rest_invalid_stored_value\",\"message\":\"The posts_per_page property has an"
                        + " invalid stored value, and cannot be updated to null.\",\"data\":{\"status\":500}}"));
        assertThat(setting("posts_per_page")).isEqualTo(IntNode.valueOf(10));
        // the main site has no WPLANG row, so the language cannot be reset either, though it reads its default
        Http.as("test-" + administrator)
                .contentType("application/json")
                .body("{\"language\":null}")
                .post(SETTINGS)
                .then()
                .statusCode(500)
                .body("code", equalTo("rest_invalid_stored_value"))
                .body(
                        "message",
                        equalTo("The language property has an invalid stored value, and cannot be updated to null."));
        assertThat(setting("language")).isEqualTo(TextNode.valueOf("en_US"));
        JsonNode reset = post("{\"title\":null}");
        assertThat(reset.get("title").isNull()).isTrue();
        assertThat(setting("title").isNull()).isTrue();
        // every parameter source counts: the query string, and a JSON null gives way to a query value
        assertThat(Http.as("test-" + administrator)
                        .contentType("application/json")
                        .body("{}")
                        .post(SETTINGS + "?title=Q")
                        .then()
                        .statusCode(200)
                        .extract()
                        .path("title")
                        .toString())
                .isEqualTo("Q");
        assertThat(Http.as("test-" + administrator)
                        .contentType("application/json")
                        .body("{\"title\":null}")
                        .post(SETTINGS + "?title=R")
                        .then()
                        .statusCode(200)
                        .extract()
                        .path("title")
                        .toString())
                .isEqualTo("R");
        assertThat(Http.as("test-" + administrator)
                        .formParam("title", "Form")
                        .post(SETTINGS)
                        .then()
                        .statusCode(200)
                        .extract()
                        .path("title")
                        .toString())
                .isEqualTo("Form");
        // values WordPress's option sanitizing refuses leave the stored value as it was, with 200
        assertThat(post("{\"timezone\":\"Mars/Base\"}").get("timezone")).isEqualTo(TextNode.valueOf(""));
        assertThat(post("{\"timezone\":\"Europe/Paris\"}").get("timezone")).isEqualTo(TextNode.valueOf("Europe/Paris"));
        assertThat(post("{\"timezone\":\"europe/paris\"}").get("timezone")).isEqualTo(TextNode.valueOf("Europe/Paris"));
        assertThat(post("{\"language\":\"fr_FR\"}").get("language")).isEqualTo(TextNode.valueOf("en_US"));
        assertThat(Jdbc.optionJson(MAIN, "WPLANG")).isNull();
        // "0" is empty to PHP, so WordPress keeps it for both
        assertThat(post("{\"timezone\":\"0\",\"language\":\"0\"}").get("language"))
                .isEqualTo(TextNode.valueOf("0"));
        assertThat(setting("timezone")).isEqualTo(TextNode.valueOf("0"));
        post("{\"timezone\":\"Europe/Paris\"}");
        // the others are changed silently
        assertThat(post("{\"date_format\":\"<b>Y</b>-m-d\"}").get("date_format"))
                .isEqualTo(TextNode.valueOf("Y-m-d"));
        assertThat(post("{\"start_of_week\":-3}").get("start_of_week")).isEqualTo(IntNode.valueOf(3));
        assertThat(post("{\"posts_per_page\":0}").get("posts_per_page")).isEqualTo(IntNode.valueOf(1));
        assertThat(post("{\"posts_per_page\":-5}").get("posts_per_page")).isEqualTo(IntNode.valueOf(5));
        assertThat(post("{\"posts_per_page\":-1}").get("posts_per_page")).isEqualTo(IntNode.valueOf(-1));
        assertThat(post("{\"posts_per_page\":\"5\"}").get("posts_per_page")).isEqualTo(IntNode.valueOf(5));
        assertThat(post("{\"site_logo\":-5}").get("site_logo")).isEqualTo(IntNode.valueOf(-5));
        assertThat(post("{\"site_icon\":-4}").get("site_icon")).isEqualTo(IntNode.valueOf(4));
        assertThat(post("{\"default_ping_status\":\"closed\"}").get("default_ping_status"))
                .isEqualTo(TextNode.valueOf("closed"));
        // WordPress stores false as an empty string, which then is no boolean: later reads show null, while the
        // write's own answer shows the value written (WordPress's option cache keeps it for the request)
        assertThat(post("{\"use_smilies\":false}").get("use_smilies")).isEqualTo(BooleanNode.FALSE);
        assertThat(Jdbc.optionJson(MAIN, "use_smilies")).isEqualTo("\"\"");
        assertThat(setting("use_smilies").isNull()).isTrue();
        // false over a stored "" changes nothing, so nothing is cached either: the answer reads the stored ""
        assertThat(post("{\"use_smilies\":false}").get("use_smilies").isNull()).isTrue();
        assertThat(post("{\"use_smilies\":\"1\"}").get("use_smilies")).isEqualTo(BooleanNode.TRUE);
        assertThat(Jdbc.optionJson(MAIN, "use_smilies")).isEqualTo("\"1\"");
        // on a network, url and email are not settings, and keys that are no setting are ignored
        JsonNode ignored = post("{\"email\":\"not-an-email\",\"url\":\"ftp://x.test\",\"qp_unknown\":1}");
        assertThat(fieldNames(ignored)).doesNotContain("url", "email", "qp_unknown");
        // the answer is a fresh read; _fields selects from it
        JsonNode fields = json(Http.as("test-" + administrator)
                .contentType("application/json")
                .body("{\"title\":\"Picked\"}")
                .post(SETTINGS + "?_fields=title")
                .then()
                .statusCode(200)
                .extract()
                .asString());
        assertThat(fields.toString()).isEqualTo("{\"title\":\"Picked\"}");
        assertThat(json(Http.as("test-" + administrator)
                                .get(SETTINGS + "?_fields=posts_per_page,use_smilies")
                                .asString())
                        .toString())
                .isEqualTo("{\"use_smilies\":true,\"posts_per_page\":5}");
    }

    @Test
    void noOptionOutsideTheRegistryIsReachable() {
        String features = Jdbc.optionJson(MAIN, "qp_features");
        JsonNode answer = post("{\"blogname\":\"x\",\"home\":\"http://evil.test\",\"WPLANG\":\"fr_FR\","
                + "\"qp_features\":{\"rest-wp-v2\":false},\"blog_public\":0,\"siteurl\":\"http://evil.test\"}");
        assertThat(answer.get("title")).isEqualTo(TextNode.valueOf("QuarkPress"));
        assertThat(Jdbc.optionJson(MAIN, "qp_features")).isEqualTo(features);
        for (String name : List.of("home", "siteurl", "blog_public", "WPLANG")) {
            assertThat(Jdbc.optionJson(MAIN, name)).as(name).isNull();
        }
        // a qp_ option is never listed, even when stored; the answer lists the registered settings
        assertThat(fieldNames(answer)).contains("title", "description").noneMatch(name -> name.startsWith("qp_"));
        Http.as("test-" + administrator)
                .get(SETTINGS + "?_fields=qp_features")
                .then()
                .statusCode(200)
                .body(equalTo("[]"));
    }

    private JsonNode post(String body) {
        return json(Http.as("test-" + administrator)
                .contentType("application/json")
                .body(body)
                .post(SETTINGS)
                .then()
                .statusCode(200)
                .extract()
                .asString());
    }

    private JsonNode setting(String name) {
        return json(Http.as("test-" + administrator).get(SETTINGS).asString()).get(name);
    }

    private String title() {
        JsonNode title = setting("title");
        return title.isNull() ? null : title.textValue();
    }

    static JsonNode json(String body) {
        try {
            return PLAIN.readTree(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    static final String OPTIONS_BODY =
            "{\"namespace\":\"wp\\/v2\",\"methods\":[\"GET\",\"POST\",\"PUT\",\"PATCH\"],\"endpoints\":[{\"method"
                    + "s\":[\"GET\"],\"args\":[]},{\"methods\":[\"POST\",\"PUT\",\"PATCH\"],\"args\":{\"title\":{\"title\":"
                    + "\"Title\",\"description\":\"Site title.\",\"type\":\"string\",\"required\":false},\"description\":{\""
                    + "title\":\"Tagline\",\"description\":\"Site tagline.\",\"type\":\"string\",\"required\":false},\"time"
                    + "zone\":{\"title\":\"\",\"description\":\"A city in the same timezone as you.\",\"type\":\"string\",\""
                    + "required\":false},\"date_format\":{\"title\":\"\",\"description\":\"A date format for all date strin"
                    + "gs.\",\"type\":\"string\",\"required\":false},\"time_format\":{\"title\":\"\",\"description\":\"A ti"
                    + "me format for all time strings.\",\"type\":\"string\",\"required\":false},\"start_of_week\":{\"title"
                    + "\":\"\",\"description\":\"A day number of the week that the week should start on.\",\"type\":\"integ"
                    + "er\",\"required\":false},\"language\":{\"title\":\"\",\"description\":\"WordPress locale code.\",\"t"
                    + "ype\":\"string\",\"required\":false},\"use_smilies\":{\"title\":\"\",\"description\":\"Convert emoti"
                    + "cons like :-) and :-P to graphics on display.\",\"type\":\"boolean\",\"required\":false},\"default_c"
                    + "ategory\":{\"title\":\"\",\"description\":\"Default post category.\",\"type\":\"integer\",\"required"
                    + "\":false},\"default_post_format\":{\"title\":\"\",\"description\":\"Default post format.\",\"type\":"
                    + "\"string\",\"required\":false},\"posts_per_page\":{\"title\":\"Maximum posts per page\",\"descriptio"
                    + "n\":\"Blog pages show at most.\",\"type\":\"integer\",\"required\":false},\"show_on_front\":{\"title"
                    + "\":\"Show on front\",\"description\":\"What to show on the front page\",\"type\":\"string\",\"requir"
                    + "ed\":false},\"page_on_front\":{\"title\":\"Page on front\",\"description\":\"The ID of the page that"
                    + " should be displayed on the front page\",\"type\":\"integer\",\"required\":false},\"page_for_posts\""
                    + ":{\"title\":\"\",\"description\":\"The ID of the page that should display the latest posts\",\"type\""
                    + ":\"integer\",\"required\":false},\"default_ping_status\":{\"title\":\"\",\"description\":\"Allow lin"
                    + "k notifications from other blogs (pingbacks and trackbacks) on new articles.\",\"type\":\"string\",\""
                    + "enum\":[\"open\",\"closed\"],\"required\":false},\"default_comment_status\":{\"title\":\"Allow comme"
                    + "nts on new posts\",\"description\":\"Allow people to submit comments on new posts.\",\"type\":\"stri"
                    + "ng\",\"enum\":[\"open\",\"closed\"],\"required\":false},\"site_logo\":{\"title\":\"Logo\",\"descript"
                    + "ion\":\"Site logo.\",\"type\":\"integer\",\"required\":false},\"site_icon\":{\"title\":\"Icon\",\"de"
                    + "scription\":\"Site icon.\",\"type\":\"integer\",\"required\":false}}}],\"schema\":{\"$schema\":\"htt"
                    + "p:\\/\\/json-schema.org\\/draft-04\\/schema#\",\"title\":\"settings\",\"type\":\"object\",\"properti"
                    + "es\":{\"title\":{\"type\":\"string\",\"title\":\"Title\",\"description\":\"Site title.\",\"default\""
                    + ":null},\"description\":{\"type\":\"string\",\"title\":\"Tagline\",\"description\":\"Site tagline.\","
                    + "\"default\":null},\"timezone\":{\"type\":\"string\",\"title\":\"\",\"description\":\"A city in the s"
                    + "ame timezone as you.\",\"default\":null},\"date_format\":{\"type\":\"string\",\"title\":\"\",\"descr"
                    + "iption\":\"A date format for all date strings.\",\"default\":null},\"time_format\":{\"type\":\"strin"
                    + "g\",\"title\":\"\",\"description\":\"A time format for all time strings.\",\"default\":null},\"start"
                    + "_of_week\":{\"type\":\"integer\",\"title\":\"\",\"description\":\"A day number of the week that the "
                    + "week should start on.\",\"default\":null},\"language\":{\"type\":\"string\",\"title\":\"\",\"descrip"
                    + "tion\":\"WordPress locale code.\",\"default\":\"en_US\"},\"use_smilies\":{\"type\":\"boolean\",\"tit"
                    + "le\":\"\",\"description\":\"Convert emoticons like :-) and :-P to graphics on display.\",\"default\""
                    + ":true},\"default_category\":{\"type\":\"integer\",\"title\":\"\",\"description\":\"Default post cate"
                    + "gory.\",\"default\":null},\"default_post_format\":{\"type\":\"string\",\"title\":\"\",\"description\""
                    + ":\"Default post format.\",\"default\":null},\"posts_per_page\":{\"type\":\"integer\",\"title\":\"Max"
                    + "imum posts per page\",\"description\":\"Blog pages show at most.\",\"default\":10},\"show_on_front\""
                    + ":{\"type\":\"string\",\"title\":\"Show on front\",\"description\":\"What to show on the front page\""
                    + ",\"default\":null},\"page_on_front\":{\"type\":\"integer\",\"title\":\"Page on front\",\"description"
                    + "\":\"The ID of the page that should be displayed on the front page\",\"default\":null},\"page_for_po"
                    + "sts\":{\"type\":\"integer\",\"title\":\"\",\"description\":\"The ID of the page that should display "
                    + "the latest posts\",\"default\":null},\"default_ping_status\":{\"type\":\"string\",\"title\":\"\",\"d"
                    + "escription\":\"Allow link notifications from other blogs (pingbacks and trackbacks) on new articles."
                    + "\",\"default\":null,\"enum\":[\"open\",\"closed\"]},\"default_comment_status\":{\"type\":\"string\","
                    + "\"title\":\"Allow comments on new posts\",\"description\":\"Allow people to submit comments on new p"
                    + "osts.\",\"default\":null,\"enum\":[\"open\",\"closed\"]},\"site_logo\":{\"type\":\"integer\",\"title"
                    + "\":\"Logo\",\"description\":\"Site logo.\",\"default\":null},\"site_icon\":{\"type\":\"integer\",\"t"
                    + "itle\":\"Icon\",\"description\":\"Site icon.\",\"default\":null}}},\"_links\":{\"self\":[{\"href\":\""
                    + "http:\\/\\/localhost\\/wp-json\\/wp\\/v2\\/settings\"}]}}";
}
