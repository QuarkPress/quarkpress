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
