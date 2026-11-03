package org.quarkpress.testing.wprestparity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RestScenarioFormatTest {

    static String scenario(String setup, String requests) {
        return "{\"id\":\"x1\",\"rule\":\"r\",\"setup\":[" + setup + "],\"requests\":[" + requests + "]}";
    }

    @Test
    void everyHandWrittenScenarioReadsAndTheVerificationListIsComplete() {
        List<RestScenario> scenarios = RestScenarios.handWritten();
        Set<String> ids = new HashSet<>();
        for (RestScenario scenario : scenarios) {
            assertThat(ids.add(scenario.id())).as("unique id %s", scenario.id()).isTrue();
            // a scenario survives being written and read back
            assertThat(RestScenarioJson.read(RestScenarioJson.write(scenario)))
                    .usingRecursiveComparison()
                    .isEqualTo(scenario);
        }
        for (int item = 1; item <= 28; item++) {
            assertThat(ids).contains("d%02d".formatted(item));
        }
        assertThat(scenarios.get(0).id()).isEqualTo("d01");
    }

    @Test
    void aRefUsedBeforeItIsDefinedIsRefusedButTheStartingRefsAreKnown() {
        String uses = "{\"as\":\"alice\",\"method\":\"GET\",\"target\":\"/wp-json/wp/v2/categories/{t:news}\"}";
        assertThatThrownBy(() -> RestScenarioJson.read(scenario("", uses)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("t:news is used before it is defined");
        String parentLater =
                "{\"term\":\"t:child\",\"as\":\"alice\",\"taxonomy\":\"category\",\"name\":\"c\",\"parent\":\"t:news\"},"
                        + "{\"term\":\"t:news\",\"as\":\"alice\",\"taxonomy\":\"category\",\"name\":\"n\"}";
        assertThatThrownBy(() -> RestScenarioJson.read(scenario(parentLater, uses)))
                .hasMessageContaining("t:news is used before it is defined");
        RestScenario fine = RestScenarioJson.read(
                scenario(
                        "{\"term\":\"t:news\",\"as\":\"bob\",\"taxonomy\":\"category\",\"name\":\"n\",\"parent\":\"t:uncategorized\"}",
                        uses
                                + ",{\"as\":\"anonymous\",\"method\":\"GET\",\"target\":\"/wp-json/wp/v2/users/{u:erin}?x={u:alice}\"}"));
        assertThat(fine.requests()).hasSize(2);
        assertThat(RestScenario.resolve(fine.requests().get(1), Map.of("u:erin", 6L, "u:alice", 2L))
                        .target())
                .isEqualTo("/wp-json/wp/v2/users/6?x=2");
    }

    @Test
    void onlyTheSevenCallersAndKnownKeysAreAccepted() {
        assertThatThrownBy(() -> RestScenarioJson.read(
                        scenario("", "{\"as\":\"mallory\",\"method\":\"GET\",\"target\":\"/wp-json/\"}")))
                .hasMessageContaining("unknown caller 'mallory'");
        assertThatThrownBy(() -> RestScenarioJson.read(scenario(
                        "{\"post\":\"p:1\",\"as\":\"anonymous\",\"type\":\"post\",\"status\":\"draft\",\"title\":\"t\"}",
                        "{\"as\":\"alice\",\"method\":\"GET\",\"target\":\"/wp-json/\"}")))
                .hasMessageContaining("unknown caller 'anonymous'");
        assertThatThrownBy(() -> RestScenarioJson.read(
                        scenario("", "{\"as\":\"alice\",\"method\":\"GET\",\"target\":\"/wp-json/\",\"bdy\":\"x\"}")))
                .hasMessageContaining("unknown key bdy");
        for (String as : RestScenario.CALLERS) {
            RestScenarioJson.read(scenario("", "{\"as\":\"" + as + "\",\"method\":\"GET\",\"target\":\"/wp-json/\"}"));
        }
    }

    @Test
    void aUserLeavesTheSiteButAliceNever() {
        String get = "{\"as\":\"bob\",\"method\":\"GET\",\"target\":\"/wp-json/wp/v2/users/me\"}";
        RestScenario read = RestScenarioJson.read(scenario("{\"leave\":\"bob\"}", get));
        assertThat(read.setup()).containsExactly(new RestScenario.SetupStep.Leave("bob"));
        assertThat(RestScenarioJson.read(RestScenarioJson.write(read)))
                .usingRecursiveComparison()
                .isEqualTo(read);
        assertThatThrownBy(() -> RestScenarioJson.read(scenario("{\"leave\":\"alice\"}", get)))
                .hasMessageContaining("alice, the super admin, never leaves a site");
        assertThatThrownBy(() -> RestScenarioJson.read(scenario("{\"leave\":\"anonymous\"}", get)))
                .hasMessageContaining("unknown caller 'anonymous'");
        assertThatThrownBy(() -> RestScenarioJson.read(scenario("{\"leave\":\"bob\",\"site\":\"x\"}", get)))
                .hasMessageContaining("unknown key site");
    }

    @Test
    void contentStepsReadAndValidate() {
        String get = "{\"as\":\"alice\",\"method\":\"GET\",\"target\":\"/wp-json/wp/v2/posts/{p:a}/revisions/{r:v1}\"}";
        String steps =
                "{\"post\":\"p:img\",\"as\":\"alice\",\"type\":\"attachment\",\"status\":\"inherit\",\"title\":\"i\"},"
                        + "{\"post\":\"p:a\",\"as\":\"carol\",\"type\":\"post\",\"status\":\"publish\",\"title\":\"t\","
                        + "\"fields\":{\"content\":\"c\",\"excerpt\":\"e\",\"password\":\"s3\",\"date_gmt\":\"2026-07-15T10:00:00\","
                        + "\"slug\":\"s\",\"menu_order\":2,\"comment_status\":\"closed\",\"sticky\":true,"
                        + "\"categories\":[\"t:uncategorized\"],\"tags\":[],\"format\":\"aside\",\"featured_media\":\"p:img\","
                        + "\"template\":\"\",\"meta\":{\"footnotes\":\"[]\"}}},"
                        + "{\"post\":\"p:child\",\"as\":\"bob\",\"type\":\"page\",\"status\":\"draft\",\"title\":\"c\",\"fields\":{\"parent\":\"p:a\"}},"
                        + "{\"revision\":\"r:v1\",\"as\":\"carol\",\"post\":\"p:a\",\"title\":\"t2\",\"content\":\"c2\"},"
                        + "{\"autosave\":\"r:auto\",\"as\":\"bob\",\"post\":\"p:a\",\"title\":\"t3\",\"content\":\"c3\"},"
                        + "{\"lock\":\"p:a\",\"user\":\"carol\",\"age\":10},"
                        + "{\"own_site\":\"dave\"}";
        RestScenario read = RestScenarioJson.read(scenario(steps, get));
        assertThat(read.setup()).hasSize(7);
        RestScenario.SetupStep.Post post =
                (RestScenario.SetupStep.Post) read.setup().get(1);
        assertThat(post.fields().get("menu_order").intValue()).isEqualTo(2);
        assertThat(post.fields().get("categories").get(0).textValue()).isEqualTo("t:uncategorized");
        assertThat(((RestScenario.SetupStep.Post) read.setup().get(0)).fields().isEmpty())
                .isTrue();
        assertThat(read.setup().get(3))
                .isEqualTo(new RestScenario.SetupStep.Revision("r:v1", "carol", "p:a", "t2", "c2"));
        assertThat(read.setup().get(4))
                .isEqualTo(new RestScenario.SetupStep.Autosave("r:auto", "bob", "p:a", "t3", "c3"));
        assertThat(read.setup().get(5)).isEqualTo(new RestScenario.SetupStep.Lock("p:a", "carol", 10));
        assertThat(read.setup().get(6)).isEqualTo(new RestScenario.SetupStep.OwnSite("dave"));
        assertThat(RestScenarioJson.read(RestScenarioJson.write(read)))
                .usingRecursiveComparison()
                .isEqualTo(read);
        assertThat(RestScenarioJson.write(read)).doesNotContain("\"fields\" : { }");
        // an r: ref is defined by a revision or an autosave step only, and resolves like the others
        assertThat(RestScenario.resolve(read.requests().get(0), Map.of("p:a", 4L, "r:v1", 9L))
                        .target())
                .isEqualTo("/wp-json/wp/v2/posts/4/revisions/9");
    }

    static final String POST_A =
            "{\"post\":\"p:a\",\"as\":\"carol\",\"type\":\"post\",\"status\":\"publish\",\"title\":\"t\"}";
    static final String GET_ROOT = "{\"as\":\"alice\",\"method\":\"GET\",\"target\":\"/\"}";

    @Test
    void anRRefIsDefinedByARevisionOrAnAutosaveOnly() {
        String get = "{\"as\":\"alice\",\"method\":\"GET\",\"target\":\"/wp-json/wp/v2/posts/{p:a}/revisions/{r:v1}\"}";
        String postA = POST_A;
        assertThatThrownBy(() -> RestScenarioJson.read(scenario(postA, get)))
                .hasMessageContaining("r:v1 is used before it is defined");
        assertThatThrownBy(() -> RestScenarioJson.read(scenario(
                        postA
                                + ",{\"revision\":\"p:v1\",\"as\":\"carol\",\"post\":\"p:a\",\"title\":\"t\",\"content\":\"c\"}",
                        get)))
                .hasMessageContaining("not a r: ref: p:v1");
        assertThatThrownBy(() -> RestScenarioJson.read(scenario(
                        "{\"post\":\"r:a\",\"as\":\"carol\",\"type\":\"post\",\"status\":\"publish\",\"title\":\"t\"}",
                        GET_ROOT)))
                .hasMessageContaining("not a p: ref: r:a");
    }

    @Test
    void aRevisionAnAutosaveOrALockNamesADefinedPostAndALockAKnownUser() {
        String postA = POST_A;
        assertThatThrownBy(() -> RestScenarioJson.read(scenario(
                        "{\"autosave\":\"r:x\",\"as\":\"carol\",\"post\":\"p:zz\",\"title\":\"t\",\"content\":\"c\"}",
                        GET_ROOT)))
                .hasMessageContaining("p:zz is used before it is defined");
        assertThatThrownBy(() -> RestScenarioJson.read(
                        scenario(postA + ",{\"lock\":\"t:uncategorized\",\"user\":\"carol\",\"age\":1}", GET_ROOT)))
                .hasMessageContaining("not a p: ref: t:uncategorized");
        assertThatThrownBy(() -> RestScenarioJson.read(
                        scenario(postA + ",{\"lock\":\"p:a\",\"user\":\"mallory\",\"age\":1}", GET_ROOT)))
                .hasMessageContaining("unknown caller 'mallory'");
    }

    @Test
    void anOwnSiteStepRefusesAlice() {
        assertThatThrownBy(() -> RestScenarioJson.read(scenario("{\"own_site\":\"alice\"}", GET_ROOT)))
                .hasMessageContaining("alice, the super admin, never leaves a site");
    }

    @Test
    void thePostFieldsAreCheckedForKeysShapesAndRefs() {
        assertThatThrownBy(() -> RestScenarioJson.read(scenario(
                        "{\"post\":\"p:a\",\"as\":\"carol\",\"type\":\"post\",\"status\":\"draft\",\"title\":\"t\",\"fields\":{\"nope\":1}}",
                        GET_ROOT)))
                .hasMessageContaining("unknown key nope");
        assertThatThrownBy(() -> RestScenarioJson.read(scenario(
                        "{\"post\":\"p:a\",\"as\":\"carol\",\"type\":\"post\",\"status\":\"draft\",\"title\":\"t\","
                                + "\"fields\":{\"categories\":[\"u:alice\"]}}",
                        GET_ROOT)))
                .hasMessageContaining("not a t: ref: u:alice");
        assertThatThrownBy(() -> RestScenarioJson.read(scenario(
                        "{\"post\":\"p:a\",\"as\":\"carol\",\"type\":\"page\",\"status\":\"draft\",\"title\":\"t\","
                                + "\"fields\":{\"parent\":\"p:zz\"}}",
                        GET_ROOT)))
                .hasMessageContaining("p:zz is used before it is defined");
        assertThatThrownBy(() -> RestScenarioJson.read(scenario(
                        "{\"post\":\"p:a\",\"as\":\"carol\",\"type\":\"post\",\"status\":\"draft\",\"title\":\"t\","
                                + "\"fields\":{\"sticky\":\"yes\"}}",
                        GET_ROOT)))
                .hasMessageContaining("sticky");
    }

    @Test
    void generatedScenariosAreReproduciblePerSeedAndValid() {
        List<RestScenario> first = RestScenarios.generated("rest-a", RestScenarios.GENERATED);
        List<RestScenario> again = RestScenarios.generated("rest-a", RestScenarios.GENERATED);
        List<RestScenario> other = RestScenarios.generated("rest-b", RestScenarios.GENERATED);
        assertThat(first).hasSize(RestScenarios.GENERATED);
        assertThat(first.stream().map(RestScenarioJson::write).toList())
                .isEqualTo(again.stream().map(RestScenarioJson::write).toList());
        assertThat(first.stream().map(RestScenarioJson::write).toList())
                .isNotEqualTo(other.stream().map(RestScenarioJson::write).toList());
        assertThat(first.get(0).id()).isEqualTo("rest-a-001");
        for (RestScenario scenario : first) {
            assertThat(scenario.requests()).hasSizeBetween(2, 6);
            for (RestCall call : scenario.requests()) {
                // the generator stays clear of request classes that differ as a whole (known differences)
                assertThat(call.as()).isNotEqualTo("invalid");
                assertThat(call.target()).doesNotContain("_jsonp");
                assertThat(call.headers()).isEmpty();
            }
            // every generated scenario reads back as written (refs defined before use)
            RestScenarioJson.read(RestScenarioJson.write(scenario));
        }
        assertThat(RestScenarios.all()).hasSize(RestScenarios.handWritten().size() + 2 * RestScenarios.GENERATED);
        assertThat(RestScenarios.isGenerated("rest-a-001")).isTrue();
        assertThat(RestScenarios.isGenerated("d01")).isFalse();
        assertThat(RestScenarios.isGenerated("x01")).isFalse();
    }
}
