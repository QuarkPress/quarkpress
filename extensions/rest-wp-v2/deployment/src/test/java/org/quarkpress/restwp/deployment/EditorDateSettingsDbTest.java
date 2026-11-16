package org.quarkpress.restwp.deployment;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

/**
 * The editor route's {@code dateSettings}, what WordPress prints into the edit screen as {@code wp.date} settings for
 * every role: the site's date and time formats, the start of the week and the time zone (its current offset in hours,
 * the stored {@code timezone_string} and its abbreviation). Members of the site: the editor bob, the contributor dave
 * and the subscriber erin. Each test restores the options it sets to the values of a new site.
 */
class EditorDateSettingsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            RestApp.network(EditorDateSettingsDbTest.class, PostFixtures.class, PostFixtures.Builder.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String EDITOR = "/wp-json/quarkpress/v1/editor/";

    /** The options a test may set, with their values on a new site. */
    private static final List<String[]> NEW_SITE = List.of(
            new String[] {"date_format", "\"F j, Y\""},
            new String[] {"time_format", "\"g:i a\""},
            new String[] {"start_of_week", "1"},
            new String[] {"timezone_string", "\"\""},
            new String[] {"gmt_offset", "\"0\""});

    static AgroalDataSource data;
    static SiteId site;
    static String host;
    static long bob;
    static long dave;
    static long erin;

    @BeforeAll
    static void members() {
        data = Jdbc.dataSource();
        site = TestData.site(data);
        host = Jdbc.strings(data, "select domain from qp_site where id = ?", site.value())
                .get(0);
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
    void defaultsOnAFreshSite() {
        Response answer = as(bob).get(EDITOR + "post/" + draftOf(bob).value());
        answer.then().statusCode(200);
        JsonNode body = json(answer);
        assertThat(names(body)).containsExactly("settings", "preload", "blockDefinitions", "dateSettings");
        assertThat(body.get("dateSettings").toString())
                .isEqualTo("{\"formats\":{\"date\":\"F j, Y\",\"time\":\"g:i a\"},\"startOfWeek\":1,"
                        + "\"timezone\":{\"offset\":0,\"string\":\"\",\"abbr\":\"\"}}");
    }

    @Test
    void storedOptionsAreCarried() {
        try {
            option("date_format", "\"d/m/Y\"");
            option("time_format", "\"H:i\"");
            // a text, as the settings route stores it
            option("start_of_week", "\"0\"");
            JsonNode dates = dateSettings(bob);
            assertThat(dates.get("formats").toString()).isEqualTo("{\"date\":\"d/m/Y\",\"time\":\"H:i\"}");
            assertThat(dates.get("startOfWeek").isIntegralNumber()).isTrue();
            assertThat(dates.get("startOfWeek").asLong()).isZero();
        } finally {
            restore();
        }
    }

    @Test
    void aNamedZoneGivesItsCurrentOffsetAndAbbreviation() {
        try {
            // a stale manual offset beside the named zone is not used
            option("gmt_offset", "\"5\"");
            option("timezone_string", "\"Europe/Paris\"");
            JsonNode zone = dateSettings(bob).get("timezone");
            assertThat(zone.get("string").textValue()).isEqualTo("Europe/Paris");
            assertThat(zone.get("offset").isIntegralNumber()).isTrue();
            assertThat(zone.get("offset").asInt()).isIn(1, 2);
            assertThat(zone.get("abbr").textValue())
                    .isEqualTo(zone.get("offset").asInt() == 1 ? "CET" : "CEST");

            option("timezone_string", "\"America/New_York\"");
            zone = dateSettings(bob).get("timezone");
            assertThat(zone.get("string").textValue()).isEqualTo("America/New_York");
            assertThat(zone.get("offset").asInt()).isIn(-5, -4);
            assertThat(zone.get("abbr").textValue())
                    .isEqualTo(zone.get("offset").asInt() == -5 ? "EST" : "EDT");

            option("timezone_string", "\"UTC\"");
            assertThat(dateSettings(bob).get("timezone").toString())
                    .isEqualTo("{\"offset\":0,\"string\":\"UTC\",\"abbr\":\"UTC\"}");

            // a zone with a name of its own at offset zero keeps it
            option("timezone_string", "\"Europe/London\"");
            zone = dateSettings(bob).get("timezone");
            assertThat(zone.get("offset").asInt()).isIn(0, 1);
            assertThat(zone.get("abbr").textValue())
                    .isEqualTo(zone.get("offset").asInt() == 0 ? "GMT" : "BST");
            // GMT all year
            option("timezone_string", "\"Atlantic/Reykjavik\"");
            assertThat(dateSettings(bob).get("timezone").toString())
                    .isEqualTo("{\"offset\":0,\"string\":\"Atlantic/Reykjavik\",\"abbr\":\"GMT\"}");
            // the time zone database abbreviates this zone numerically in every season, whatever Java calls it
            option("timezone_string", "\"Antarctica/Troll\"");
            zone = dateSettings(bob).get("timezone");
            assertThat(zone.get("offset").asInt()).isIn(0, 2);
            assertThat(zone.get("abbr").textValue())
                    .isEqualTo(zone.get("offset").asInt() == 0 ? "+00" : "+02");

            // zones whose abbreviation is numeric in the time zone database: PHP's numeric form
            option("timezone_string", "\"America/Sao_Paulo\"");
            assertThat(dateSettings(bob).get("timezone").toString())
                    .isEqualTo("{\"offset\":-3,\"string\":\"America/Sao_Paulo\",\"abbr\":\"-03\"}");
            option("timezone_string", "\"Asia/Kathmandu\"");
            assertThat(dateSettings(bob).get("timezone").toString())
                    .isEqualTo("{\"offset\":5.75,\"string\":\"Asia/Kathmandu\",\"abbr\":\"+0545\"}");
        } finally {
            restore();
        }
    }

    @Test
    void aManualOffset() {
        try {
            option("timezone_string", "\"\"");
            option("gmt_offset", "\"5.5\"");
            assertThat(dateSettings(bob).get("timezone").toString())
                    .isEqualTo("{\"offset\":5.5,\"string\":\"\",\"abbr\":\"\"}");

            option("gmt_offset", "\"-3.5\"");
            assertThat(dateSettings(bob).get("timezone").toString())
                    .isEqualTo("{\"offset\":-3.5,\"string\":\"\",\"abbr\":\"\"}");

            option("gmt_offset", "\"0\"");
            Response answer = as(bob).get(EDITOR + "post/" + draftOf(bob).value());
            answer.then().statusCode(200);
            assertThat(answer.asString()).contains("\"timezone\":{\"offset\":0,\"string\"");

            // the stored value as PHP's float cast reads it, also where no zone offset can follow
            option("gmt_offset", "\"5.33\"");
            assertThat(dateSettings(bob).get("timezone").toString())
                    .isEqualTo("{\"offset\":5.33,\"string\":\"\",\"abbr\":\"\"}");
            option("gmt_offset", "\"20\"");
            assertThat(dateSettings(bob).get("timezone").toString())
                    .isEqualTo("{\"offset\":20,\"string\":\"\",\"abbr\":\"\"}");
            option("gmt_offset", "\" -2.75 hours\"");
            assertThat(dateSettings(bob).get("timezone").toString())
                    .isEqualTo("{\"offset\":-2.75,\"string\":\"\",\"abbr\":\"\"}");

            // a text that names no zone gives the manual offset and no abbreviation
            option("timezone_string", "\"Mars/Olympus\"");
            option("gmt_offset", "\"2\"");
            assertThat(dateSettings(bob).get("timezone").toString())
                    .isEqualTo("{\"offset\":2,\"string\":\"Mars/Olympus\",\"abbr\":\"\"}");
        } finally {
            restore();
        }
    }

    @Test
    void missingOptionsFallBackAsWordPress() {
        try {
            for (String[] option : NEW_SITE) {
                TestData.deleteOption(data, site, option[0]);
            }
            assertThat(dateSettings(bob).toString())
                    .isEqualTo("{\"formats\":{\"date\":\"F j, Y\",\"time\":\"g:i a\"},\"startOfWeek\":0,"
                            + "\"timezone\":{\"offset\":0,\"string\":\"UTC\",\"abbr\":\"UTC\"}}");
            // without a time zone row the offset is the stored manual one
            option("gmt_offset", "\"5.5\"");
            assertThat(dateSettings(bob).get("timezone").toString())
                    .isEqualTo("{\"offset\":5.5,\"string\":\"UTC\",\"abbr\":\"UTC\"}");
        } finally {
            restore();
        }
    }

    @Test
    void aContributorGetsThemTooAndASubscriberNothing() {
        try {
            option("date_format", "\"Y-m-d\"");
            option("timezone_string", "\"Europe/Paris\"");
            JsonNode bobs = dateSettings(bob);
            assertThat(dateSettings(dave)).isEqualTo(bobs);

            Response refused = as(erin).get(EDITOR + "post/" + draftOf(erin).value());
            refused.then().statusCode(403).body("code", equalTo("rest_cannot_edit"));
            assertThat(refused.asString()).doesNotContain("dateSettings");
        } finally {
            restore();
        }
    }

    // ---- helpers

    /** The date settings the editor route answers the user on a draft of their own. */
    private static JsonNode dateSettings(long user) {
        Response answer = as(user).get(EDITOR + "post/" + draftOf(user).value());
        answer.then().statusCode(200);
        JsonNode dates = json(answer).get("dateSettings");
        assertThat(dates).as("dateSettings").isNotNull();
        return dates;
    }

    private static void option(String name, String json) {
        TestData.siteOption(data, site, name, json);
    }

    private static void restore() {
        for (String[] option : NEW_SITE) {
            option(option[0], option[1]);
        }
    }

    /** A request to the test site as the user with this id. */
    private static RequestSpecification as(long user) {
        return Http.at(host, "test-" + user);
    }

    private static PostId draftOf(long author) {
        return PostFixtures.builder(site)
                .status("draft")
                .author(author)
                .title("Drafted " + UUID.randomUUID())
                .publishedAt(null)
                .insert(data);
    }

    private static List<String> names(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static JsonNode json(Response response) {
        try {
            return MAPPER.readTree(response.asString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
