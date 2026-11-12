package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

/**
 * The oEmbed proxy ({@code GET /oembed/1.0/proxy}) against a local provider: WordPress's validation before its
 * permission (Part G fact v11), the untrusted filter on every provider but the configured trusted ones, the cache
 * keyed by the site and every parameter (provider successes only), non-object and oversized bodies refused, the
 * embed handlers for audio, video and YouTube embed URLs (fact v12), the site's own URLs refused, and the parameters
 * the provider receives (600 and 750 for an absent or zero size). The site's members: the editor bob and the
 * subscriber erin; bob is also an editor of a second site.
 */
class OEmbedProxyDbTest {

    static final LocalProvider PROVIDER = LocalProvider.get();

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(OEmbedProxyDbTest.class, LocalProvider.class)
            .overrideConfigKey("quarkpress.outbound.allow-private-addresses", "true")
            .overrideConfigKey("quarkpress.outbound.allowed-ports", "80,443,8080," + PROVIDER.port())
            .overrideConfigKey("quarkpress.oembed.provider-endpoints.local", PROVIDER.endpoint("oembed"))
            .overrideConfigKey("quarkpress.oembed.provider-schemes.local", "https://local.invalid/*")
            .overrideConfigKey("quarkpress.oembed.provider-endpoints.trusted", PROVIDER.endpoint("trusted"))
            .overrideConfigKey("quarkpress.oembed.provider-schemes.trusted", "https://trusted.invalid/*")
            // replaces the snapshot's YouTube (whose schemes include the embed URLs) so that no request leaves the host
            .overrideConfigKey("quarkpress.oembed.provider-endpoints.YouTube", PROVIDER.endpoint("youtube"))
            .overrideConfigKey(
                    "quarkpress.oembed.provider-schemes.YouTube",
                    "https://youtube.com/watch*,https://*.youtube.com/watch*")
            // an endpoint that already carries a query: WordPress replaces its format and dnt in place
            .overrideConfigKey("quarkpress.oembed.provider-endpoints.query", PROVIDER.endpoint("q?format=xml&dnt=0"))
            .overrideConfigKey("quarkpress.oembed.provider-schemes.query", "https://query.invalid/*")
            .overrideConfigKey("quarkpress.oembed.trusted-providers", "trusted");

    private static final String PROXY = "/wp-json/oembed/1.0/proxy";

    private static final String FORBIDDEN = "Sorry, you are not allowed to make proxied oEmbed requests.";

    private static final String NOT_FOUND =
            "{\"code\":\"oembed_invalid_url\",\"message\":\"Not Found\"," + "\"data\":{\"status\":404}}";

    private static final ObjectMapper JSON = new ObjectMapper();

    static AgroalDataSource data;
    static SiteId site;
    static String host;
    static SiteId other;
    static String otherHost;
    static long bob;
    static long erin;

    @BeforeAll
    static void fixtures() {
        data = Jdbc.dataSource();
        site = TestData.siteIn(data, "UTC");
        host = domain(site);
        other = TestData.siteIn(data, "UTC");
        otherHost = domain(other);
        UserId editor = TestData.profile(data, "editor");
        TestData.member(data, site, editor, "[\"editor\"]");
        TestData.member(data, other, editor, "[\"editor\"]");
        bob = editor.value();
        UserId subscriber = TestData.profile(data, "subscriber");
        TestData.member(data, site, subscriber, "[\"subscriber\"]");
        erin = subscriber.value();
    }

    private static String domain(SiteId site) {
        return Jdbc.strings(data, "select domain from qp_site where id = ?", site.value())
                .get(0);
    }

    /** A request to the site as the user, or anonymous for 0. */
    static RequestSpecification as(long user) {
        return Http.at(host, user == 0 ? null : "test-" + user);
    }

    private static Response proxy(long user, String url, Map<String, String> more) {
        RequestSpecification spec = as(user);
        if (url != null) {
            spec.queryParam("url", url);
        }
        more.forEach(spec::queryParam);
        return spec.get(PROXY);
    }

    private static Response proxy(long user, String url) {
        return proxy(user, url, Map.of());
    }

    /** The body with PHP's escaped slashes unescaped, for comparisons written plainly. */
    static String plain(Response response) {
        return response.asString().replace("\\/", "/");
    }

    static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void validationThenPermission() {
        // no url: the missing-parameter error, before the permission (erin and anonymous get it too), with Allow
        String missing = "{\"code\":\"rest_missing_callback_param\",\"message\":\"Missing parameter(s): url\","
                + "\"data\":{\"status\":400,\"params\":[\"url\"]}}";
        Response none = proxy(bob, null);
        none.then().statusCode(400).header("Allow", "GET");
        assertThat(none.asString()).isEqualTo(missing);
        assertThat(proxy(erin, null).asString()).isEqualTo(missing);
        assertThat(proxy(0, null).asString()).isEqualTo(missing);
        // format outside its enum
        Response format = proxy(bob, "https://pages.invalid/page", Map.of("format", "foo"));
        format.then().statusCode(400).header("Allow", "GET");
        assertThat(format.asString())
                .isEqualTo("{\"code\":\"rest_invalid_param\",\"message\":\"Invalid parameter(s): format\","
                        + "\"data\":{\"status\":400,\"params\":{\"format\":\"format is not one of json and xml.\"},"
                        + "\"details\":{\"format\":{\"code\":\"rest_not_in_enum\",\"message\":\"format is not one"
                        + " of json and xml.\",\"data\":null}}}}");
        // maxwidth=abc is not refused: it is read as 0 (fact v11); the unknown URL then answers 404
        Response abc = proxy(bob, "https://pages.invalid/page", Map.of("maxwidth", "abc", "maxheight", "x"));
        abc.then().statusCode(404).header("Allow", "GET");
        assertThat(abc.asString()).isEqualTo(NOT_FOUND);
        // the permission: edit_posts, else WordPress's message with the auth code
        Response subscriber = proxy(erin, "https://pages.invalid/page");
        subscriber.then().statusCode(403);
        assertThat(subscriber.asString())
                .isEqualTo("{\"code\":\"rest_forbidden\",\"message\":\"" + FORBIDDEN + "\",\"data\":{\"status\":403}}");
        Response anonymous = proxy(0, "https://pages.invalid/page");
        anonymous.then().statusCode(401);
        assertThat(anonymous.asString())
                .isEqualTo("{\"code\":\"rest_forbidden\",\"message\":\"" + FORBIDDEN + "\",\"data\":{\"status\":401}}");
        // url is never checked as a URI, only sanitized: notaurl becomes http://notaurl, which nothing serves
        Response notaurl = proxy(bob, "notaurl");
        notaurl.then().statusCode(404);
        assertThat(notaurl.asString()).isEqualTo(NOT_FOUND);
        // format=xml is accepted and changes nothing: the answer is JSON
        Response xml = proxy(bob, "https://pages.invalid/page", Map.of("format", "xml", "discover", "false"));
        xml.then().statusCode(404).contentType("application/json; charset=UTF-8");
        // the route as WordPress describes it (fact v11)
        Response options = as(bob).options(PROXY);
        options.then().statusCode(200).header("Allow", "GET");
        assertThat(plain(options))
                .isEqualTo("{\"namespace\":\"oembed/1.0\",\"methods\":[\"GET\"],\"endpoints\":[{\"methods\":[\"GET\"],"
                        + "\"args\":{\"url\":{\"description\":\"The URL of the resource for which to fetch oEmbed"
                        + " data.\",\"type\":\"string\",\"format\":\"uri\",\"required\":true},\"format\":{"
                        + "\"description\":\"The oEmbed format to use.\",\"type\":\"string\",\"default\":\"json\","
                        + "\"enum\":[\"json\",\"xml\"],\"required\":false},\"maxwidth\":{\"description\":\"The"
                        + " maximum width of the embed frame in pixels.\",\"type\":\"integer\",\"default\":600,"
                        + "\"required\":false},\"maxheight\":{\"description\":\"The maximum height of the embed"
                        + " frame in pixels.\",\"type\":\"integer\",\"required\":false},\"discover\":{"
                        + "\"description\":\"Whether to perform an oEmbed discovery request for unsanctioned"
                        + " providers.\",\"type\":\"boolean\",\"default\":true,\"required\":false}}}],\"_links\":{"
                        + "\"self\":[{\"href\":\"http://" + host + "/wp-json/oembed/1.0/proxy\"}]}}");
    }

    @Test
    void anUntrustedProviderIsFiltered() {
        Response response = proxy(bob, "https://local.invalid/rich/1");
        response.then().statusCode(200).header("Allow", "GET");
        JsonNode body = json(response.asString());
        // the provider's members as received, html replaced in place
        assertThat(fieldNames(body))
                .containsExactly("version", "type", "title", "provider_name", "width", "height", "html");
        assertThat(body.get("title").textValue()).isEqualTo("Rich & \"quoted\"");
        String html = body.get("html").textValue();
        // the script is gone; the blockquote does not open the sanitized HTML (the script's text does), so only
        // the last closed iframe survives, as WordPress's extraction keeps it, titled and with its secret
        assertThat(html)
                .doesNotContain("<script")
                .doesNotContain("blockquote")
                .doesNotContain("frame#")
                .doesNotContain("allow=")
                .doesNotContain("\n");
        String secret = html.substring(html.indexOf("data-secret=\"") + 13, html.indexOf("data-secret=\"") + 23);
        assertThat(secret).matches("[A-Za-z0-9]{10}");
        assertThat(html)
                .isEqualTo("<iframe class=\"wp-embedded-content\" sandbox=\"allow-scripts\" security=\"restricted\""
                        + " title=\"Rich &amp; &quot;quoted&quot;\" src=\"https://local.invalid/second#?secret="
                        + secret + "\" data-secret=\"" + secret + "\"></iframe>");
        // a provider whose html opens with the blockquote keeps it, with the secret on both and the iframe hidden
        String quoted = json(proxy(bob, "https://local.invalid/quoted/1").asString())
                .get("html")
                .textValue();
        String quotedSecret =
                quoted.substring(quoted.indexOf("data-secret=\"") + 13, quoted.indexOf("data-secret=\"") + 23);
        assertThat(quoted)
                .isEqualTo("<blockquote class=\"wp-embedded-content\" data-secret=\"" + quotedSecret
                        + "\"><a href=\"https://local.invalid/quoted/1\">Quoted</a></blockquote><iframe"
                        + " class=\"wp-embedded-content\" sandbox=\"allow-scripts\" security=\"restricted\""
                        + " style=\"position: absolute; visibility: hidden;\" title=\"Quoted\""
                        + " src=\"https://local.invalid/frame#?secret=" + quotedSecret + "\" data-secret=\""
                        + quotedSecret + "\" width=\"100\" height=\"50\"></iframe>");
        // a photo: the link around the image, no filter (not video or rich)
        JsonNode photo = json(proxy(bob, "https://local.invalid/photo/1").asString());
        assertThat(photo.get("html").textValue())
                .isEqualTo("<a href=\"https://local.invalid/photo/1\"><img src=\"https://local.invalid/i.jpg\""
                        + " alt=\"Photo\" width=\"10\" height=\"20\" /></a>");
        // an error page whose body is a JSON object is accepted as oEmbed data
        proxy(bob, "https://local.invalid/error404/1").then().statusCode(200);
    }

    @Test
    void aTrustedProviderPassesThrough() {
        Response response = proxy(bob, "https://trusted.invalid/newline/1");
        response.then().statusCode(200);
        // the script and the other attributes stay; the title goes into the iframe; newlines go, except in the pre
        assertThat(json(response.asString()).get("html").textValue())
                .isEqualTo("<script>x()</script><iframe title=\"Trusted\" src=\"https://trusted.invalid/f\""
                        + " allow=\"camera\"></iframe><pre>a\nb</pre>");
    }

    @Test
    void successesAreCachedPerParameters() {
        String url = "https://local.invalid/count/1";
        String first = json(proxy(bob, url).asString()).get("html").textValue();
        // the same request again: answered from the cache, with the same secret, one provider hit
        assertThat(json(proxy(bob, url).asString()).get("html").textValue()).isEqualTo(first);
        assertThat(PROVIDER.requestsFor(url)).hasSize(1);
        // another parameter value: a second hit
        proxy(bob, url, Map.of("maxwidth", "300")).then().statusCode(200);
        assertThat(PROVIDER.requestsFor(url)).hasSize(2);
        // any other query parameter counts, _wpnonce does not
        proxy(bob, url, Map.of("_wpnonce", "abc")).then().statusCode(200);
        assertThat(PROVIDER.requestsFor(url)).hasSize(2);
        proxy(bob, url, Map.of("_locale", "user")).then().statusCode(200);
        assertThat(PROVIDER.requestsFor(url)).hasSize(3);
        // another site: a third hit for the plain request
        Http.at(otherHost, "test-" + bob)
                .queryParam("url", url)
                .get(PROXY)
                .then()
                .statusCode(200);
        assertThat(PROVIDER.requestsFor(url)).hasSize(4);
    }

    @Test
    void failuresAreNotCached() {
        String url = "https://local.invalid/fail/1";
        for (int i = 0; i < 2; i++) {
            Response response = proxy(bob, url);
            response.then().statusCode(404);
            assertThat(response.asString()).isEqualTo(NOT_FOUND);
        }
        assertThat(PROVIDER.requestsFor(url)).hasSize(2);
    }

    @Test
    void aNonObjectBodyIsNotFound() {
        for (String url : List.of(
                "https://local.invalid/list/1",
                "https://local.invalid/empty/1",
                "https://local.invalid/big/1",
                "https://local.invalid/status501/1")) {
            Response response = proxy(bob, url);
            response.then().statusCode(404);
            assertThat(response.asString()).as(url).isEqualTo(NOT_FOUND);
        }
    }

    @Test
    void handlersAndOwnSite() {
        // audio and video: shortcode text, as WordPress's handlers answer (fact v12)
        Response audio = proxy(bob, "https://media.invalid/a.mp3");
        audio.then().statusCode(200).header("Allow", "GET");
        assertThat(plain(audio))
                .isEqualTo(
                        "{\"provider_name\":\"Embed Handler\",\"html\":\"[audio src=\\\"https://media.invalid/a.mp3\\\""
                                + " /]\",\"scripts\":[]}");
        assertThat(plain(proxy(bob, "https://media.invalid/A.MP4")))
                .isEqualTo(
                        "{\"provider_name\":\"Embed Handler\",\"html\":\"[video  src=\\\"https://media.invalid/A.MP4\\\""
                                + " /]\",\"scripts\":[]}");
        // the sizes go into the video shortcode only when both were sent
        assertThat(plain(proxy(bob, "https://media.invalid/a.webm", Map.of("maxheight", "300"))))
                .isEqualTo(
                        "{\"provider_name\":\"Embed Handler\",\"html\":\"[video width=\\\"600\\\" height=\\\"300\\\" "
                                + " src=\\\"https://media.invalid/a.webm\\\" /]\",\"scripts\":[]}");
        assertThat(plain(proxy(bob, "https://media.invalid/b.webm", Map.of("maxwidth", "0", "maxheight", "300"))))
                .isEqualTo(
                        "{\"provider_name\":\"Embed Handler\",\"html\":\"[video  src=\\\"https://media.invalid/b.webm\\\""
                                + " /]\",\"scripts\":[]}");
        // the extension must end the URL, and something must come before its dot
        assertThat(plain(proxy(bob, "https://media.invalid/a.mp3?x=1"))).isEqualTo(NOT_FOUND);
        assertThat(plain(proxy(bob, "https://.mp3"))).isEqualTo(NOT_FOUND);
        // the shortcode's URL is the sanitized URL as it is, no display entities
        assertThat(plain(proxy(bob, "https://media.invalid/a&b.mp3")))
                .isEqualTo(
                        "{\"provider_name\":\"Embed Handler\",\"html\":\"[audio src=\\\"https://media.invalid/a&b.mp3\\\""
                                + " /]\",\"scripts\":[]}");
        // a YouTube embed URL: the watch URL through the provider path; failing, the bare watch URL (fact v12)
        assertThat(plain(proxy(bob, "https://www.youtube.com/embed/x")))
                .isEqualTo("{\"provider_name\":\"Embed Handler\",\"html\":\"https://youtube.com/watch?v=x\","
                        + "\"scripts\":[]}");
        JsonNode ok = json(proxy(bob, "https://youtube.com/v/ok").asString());
        assertThat(fieldNames(ok)).containsExactly("provider_name", "html", "scripts");
        assertThat(ok.get("provider_name").textValue()).isEqualTo("Embed Handler");
        assertThat(ok.get("html").textValue())
                .startsWith("<iframe class=\"wp-embedded-content\" sandbox=\"allow-scripts\"")
                .contains("https://local.invalid/second#?secret=");
        assertThat(PROVIDER.requestsFor("https://youtube.com/watch?v=ok")).hasSize(1);
        // nothing matched: 404, and nothing left the host
        assertThat(plain(proxy(bob, "https://pages.invalid/page"))).isEqualTo(NOT_FOUND);
        assertThat(PROVIDER.requestsFor("https://pages.invalid/page")).isEmpty();
        // the site's own URLs, and a sibling site's, are not proxied
        for (String own : List.of(
                "http://" + host + "/?p=1",
                "http://" + host.toUpperCase(java.util.Locale.ROOT) + "/",
                "https://" + host + "/a.mp3",
                "http://" + otherHost + "/?p=1")) {
            Response response = proxy(bob, own);
            response.then().statusCode(404);
            assertThat(response.asString()).as(own).isEqualTo(NOT_FOUND);
        }
        assertThat(PROVIDER.requestsFor("https://" + host + "/a.mp3")).isEmpty();
    }

    @Test
    void theProviderGetsWordPresssParameters() {
        String url = "https://local.invalid/params/1";
        proxy(bob, url).then().statusCode(200);
        List<String> seen = PROVIDER.requestsFor(url);
        assertThat(seen).hasSize(1);
        // in WordPress's order, the URL encoded, dnt=1, then format=json
        assertThat(seen.get(0))
                .isEqualTo("/oembed?maxwidth=600&maxheight=750&url=https%3A%2F%2Flocal.invalid%2Fparams%2F1&dnt=1"
                        + "&format=json");
        // a non-numeric or zero size sends the defaults; negative values their absolute value; a size is passed on
        proxy(bob, url, Map.of("maxwidth", "abc", "maxheight", "0")).then().statusCode(200);
        proxy(bob, url, Map.of("maxwidth", "-5", "maxheight", "200")).then().statusCode(200);
        proxy(bob, url, Map.of("maxwidth", "300")).then().statusCode(200);
        seen = PROVIDER.requestsFor(url);
        assertThat(seen).hasSize(4);
        assertThat(LocalProvider.query(seen.get(1)))
                .containsEntry("maxwidth", "600")
                .containsEntry("maxheight", "750");
        assertThat(LocalProvider.query(seen.get(2)))
                .containsEntry("maxwidth", "5")
                .containsEntry("maxheight", "200");
        assertThat(LocalProvider.query(seen.get(3)))
                .containsEntry("maxwidth", "300")
                .containsEntry("maxheight", "750");
        // an endpoint's own format and dnt are replaced where they stand, the rest appended
        String queried = "https://query.invalid/params/1";
        proxy(bob, queried).then().statusCode(200);
        assertThat(PROVIDER.requestsFor(queried))
                .containsExactly("/q?format=json&dnt=1&maxwidth=600&maxheight=750"
                        + "&url=https%3A%2F%2Fquery.invalid%2Fparams%2F1");
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
