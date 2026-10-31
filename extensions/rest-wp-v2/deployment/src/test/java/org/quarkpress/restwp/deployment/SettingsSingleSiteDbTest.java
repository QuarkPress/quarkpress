package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.quarkpress.restwp.deployment.TestData.MAIN;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.restwp.SettingDefinition;
import org.quarkpress.restwp.SettingsProvider;
import org.quarkpress.restwp.SettingsRegistry;

/**
 * {@code /wp/v2/settings} on a single site, where {@code url} and {@code email} are settings too, and with two settings
 * an application adds through {@link SettingsProvider} beans, which follow the built-ins ordered by class name. A
 * setting that is not shown in REST is neither listed nor written.
 */
class SettingsSingleSiteDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.singleSite(
            SettingsSingleSiteDbTest.class,
            SettingsSingleSiteDbTest.ZetaSettings.class,
            SettingsSingleSiteDbTest.AlphaSettings.class);

    static final String SETTINGS = "/wp-json/wp/v2/settings";

    /** Registered second: its class name sorts after {@link AlphaSettings}'s. */
    @ApplicationScoped
    public static class ZetaSettings implements SettingsProvider {
        @Override
        public List<SettingDefinition> settings() {
            ObjectNode schema = JsonNodeFactory.instance.objectNode();
            schema.put("title", "Zeta");
            schema.put("description", "How many zetas.");
            schema.put("minimum", 1);
            return List.of(new SettingDefinition(
                    "zeta_count", "app_zeta_count", "integer", Optional.of(IntNode.valueOf(3)), schema, true));
        }
    }

    @ApplicationScoped
    public static class AlphaSettings implements SettingsProvider {
        @Override
        public List<SettingDefinition> settings() {
            ObjectNode hidden = JsonNodeFactory.instance.objectNode();
            return List.of(
                    new SettingDefinition(
                            "alpha_flag",
                            "app_alpha_flag",
                            "boolean",
                            Optional.empty(),
                            JsonNodeFactory.instance.objectNode(),
                            true),
                    new SettingDefinition(
                            "alpha_secret", "app_alpha_secret", "string", Optional.empty(), hidden, false));
        }
    }

    @Inject
    SettingsRegistry registry;

    long administrator;
    long editor;

    @BeforeEach
    void members() {
        administrator =
                TestData.memberOfMain(Jdbc.dataSource(), "administrator").value();
        editor = TestData.memberOfMain(Jdbc.dataSource(), "editor").value();
        for (String name : List.of("siteurl", "admin_email", "app_zeta_count", "app_alpha_flag", "app_alpha_secret")) {
            Jdbc.deleteOption(MAIN, name);
        }
    }

    @Test
    void urlAndEmailFollowTheDescriptionAndApplicationSettingsFollowTheBuiltIns() {
        JsonNode settings = json(Http.as("test-" + administrator).get(SETTINGS).asString());
        assertThat(fieldNames(settings))
                .containsExactly(
                        "title",
                        "description",
                        "url",
                        "email",
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
                        "site_icon",
                        "alpha_flag",
                        "zeta_count");
        // an option that does not exist and has no default reads as PHP's false, which is a boolean
        assertThat(settings.get("alpha_flag")).isEqualTo(BooleanNode.FALSE);
        assertThat(settings.get("zeta_count")).isEqualTo(IntNode.valueOf(3));
        // the registry lists every setting, the hidden one included, in the same order
        assertThat(registry.all().stream().map(SettingDefinition::restName).toList())
                .endsWith("site_icon", "alpha_flag", "alpha_secret", "zeta_count");
        assertThat(registry.find("alpha_secret")).isPresent();
        assertThat(registry.find("url").map(SettingDefinition::optionName)).contains("siteurl");
        assertThat(registry.find("nope")).isEmpty();
    }

    @Test
    void theRouteDescribesUrlEmailAndApplicationSettings() {
        JsonNode options =
                json(Http.as("test-" + administrator).options(SETTINGS).asString());
        // WordPress's single-site description of url and email (checked on a WordPress single site)
        assertThat(options.at("/endpoints/1/args/url").toString())
                .isEqualTo("{\"title\":\"\",\"description\":\"Site URL.\",\"type\":\"string\",\"format\":\"uri\","
                        + "\"required\":false}");
        assertThat(options.at("/endpoints/1/args/email").toString())
                .isEqualTo("{\"title\":\"\",\"description\":\"This address is used for admin purposes, like new user"
                        + " notification.\",\"type\":\"string\",\"format\":\"email\",\"required\":false}");
        assertThat(options.at("/schema/properties/url").toString())
                .isEqualTo("{\"type\":\"string\",\"title\":\"\",\"description\":\"Site URL.\",\"default\":null,"
                        + "\"format\":\"uri\"}");
        assertThat(options.at("/schema/properties/zeta_count").toString())
                .isEqualTo("{\"type\":\"integer\",\"title\":\"Zeta\",\"description\":\"How many zetas.\",\"default\":3,"
                        + "\"minimum\":1}");
        assertThat(options.at("/endpoints/1/args/alpha_flag").toString())
                .isEqualTo("{\"title\":\"\",\"description\":\"\",\"type\":\"boolean\",\"required\":false}");
        assertThat(options.at("/endpoints/1/args").has("alpha_secret")).isFalse();
        assertThat(options.at("/schema/properties").has("alpha_secret")).isFalse();
    }

    @Test
    void emailMustBeAnAddressAndUrlIsTheSiteAddress() {
        Http.as("test-" + administrator)
                .contentType("application/json")
                .body("{\"email\":\"nope\"}")
                .post(SETTINGS)
                .then()
                .statusCode(400)
                .header("Allow", "GET, POST, PUT, PATCH")
                .body(equalTo("{\"code\":\"rest_invalid_param\",\"message\":\"Invalid parameter(s): email\",\"data\":"
                        + "{\"status\":400,\"params\":{\"email\":\"Invalid email address.\"},\"details\":{\"email\":"
                        + "{\"code\":\"rest_invalid_email\",\"message\":\"Invalid email address.\",\"data\":null}}}}"));
        assertThat(post("{\"email\":\"x@example.com\"}").get("email")).isEqualTo(TextNode.valueOf("x@example.com"));
        // url is the site's own address, as the index's url gives it; a write of it is checked, then ignored
        String address =
                json(Http.as(null).get("/wp-json/").asString()).get("url").textValue();
        assertThat(address).isEqualTo("http://localhost");
        assertThat(post("{}").get("url")).isEqualTo(TextNode.valueOf(address));
        for (String written : List.of("http://site.test/a/", "ftp://x.test", "javascript:alert(1)", "/relative", "")) {
            assertThat(post("{\"url\":\"" + written + "\",\"title\":\"T\"}").get("url"))
                    .as(written)
                    .isEqualTo(TextNode.valueOf(address));
        }
        assertThat(post("{\"url\":null}").get("url")).isEqualTo(TextNode.valueOf(address));
        assertThat(Jdbc.optionJson(MAIN, "siteurl")).isNull();
        Http.as("test-" + administrator)
                .contentType("application/json")
                .body("{\"url\":5}")
                .post(SETTINGS)
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_invalid_param"))
                .body("data.params.url", equalTo("url is not of type string."));
    }

    @Test
    void applicationSettingsAreValidatedSanitizedAndHiddenOnesUnreachable() {
        Http.as("test-" + administrator)
                .contentType("application/json")
                .body("{\"zeta_count\":0}")
                .post(SETTINGS)
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_invalid_param"))
                .body("data.params.zeta_count", equalTo("zeta_count must be greater than or equal to 1"));
        JsonNode written = post("{\"zeta_count\":\"7\",\"alpha_flag\":true,\"alpha_secret\":\"leak\"}");
        assertThat(written.get("zeta_count")).isEqualTo(IntNode.valueOf(7));
        assertThat(written.get("alpha_flag")).isEqualTo(BooleanNode.TRUE);
        assertThat(written.has("alpha_secret")).isFalse();
        assertThat(Jdbc.optionJson(MAIN, "app_alpha_secret")).isNull();
        assertThat(post("{\"zeta_count\":null}").get("zeta_count")).isEqualTo(IntNode.valueOf(3));
        // a boolean setting without a default reads false once deleted, so its null reset is accepted
        assertThat(post("{\"alpha_flag\":null}").get("alpha_flag")).isEqualTo(BooleanNode.FALSE);
        assertThat(post("{\"alpha_flag\":null}").get("alpha_flag")).isEqualTo(BooleanNode.FALSE);
        Http.as("test-" + editor)
                .get(SETTINGS)
                .then()
                .statusCode(403)
                .header("Allow", nullValue())
                .body("code", equalTo("rest_forbidden"));
    }

    private static JsonNode json(String body) {
        try {
            return new ObjectMapper().readTree(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
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
}
