package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class MultisiteScenariosTest {

    @Test
    void theHandWrittenScenariosCoverTheLookupAndCreationRules() {
        List<Scenario> scenarios = MultisiteScenarios.handWritten();
        assertThat(scenarios.stream().map(Scenario::rule).collect(Collectors.toSet()))
                .contains("MS2", "MS3", "MS4", "MS6", "MS7", "MS8", "MS9", "MS10", "MS11", "MS13");
        assertThat(scenarios).extracting(Scenario::caseId).doesNotHaveDuplicates();
        assertThat(scenarios).noneMatch(Scenario::generated);
    }

    @Test
    void ruleCoverageIncludesBothInstallTypesAndTheNetworkPath() {
        List<Scenario> scenarios = MultisiteScenarios.handWritten();
        for (String rule : List.of("MS7", "MS11")) {
            assertThat(scenarios)
                    .filteredOn(scenario -> scenario.rule().equals(rule))
                    .extracting(scenario -> scenario.variant().subdomainInstall())
                    .contains(true, false);
        }
        assertThat(scenarios)
                .filteredOn(scenario -> scenario.rule().equals("MS6"))
                .extracting(scenario -> scenario.variant().name())
                .contains("subdirectory-net");
    }

    @Test
    void mostGeneratedLookupsStartFromACreatedSite() {
        for (NetworkVariant variant : NetworkVariant.ALL) {
            long lookups = 0;
            long reaching = 0;
            for (Scenario scenario : MultisiteScenarios.generated(variant, "test-seed", 40)) {
                List<ScenarioOp.CreateSite> creates = scenario.ops().stream()
                        .filter(ScenarioOp.CreateSite.class::isInstance)
                        .map(ScenarioOp.CreateSite.class::cast)
                        .toList();
                for (ScenarioOp op : scenario.ops()) {
                    if (op instanceof ScenarioOp.Lookup lookup) {
                        lookups++;
                        if (creates.stream().anyMatch(site -> reaches(site, lookup))) {
                            reaching++;
                        }
                    }
                }
            }
            assertThat(reaching * 100 / lookups).as(variant.name()).isGreaterThanOrEqualTo(60);
        }
    }

    /** The lookup's host is the site's domain (ignoring case and www.) and its path starts with the site's path. */
    static boolean reaches(ScenarioOp.CreateSite site, ScenarioOp.Lookup lookup) {
        String host = lookup.host().toLowerCase(java.util.Locale.ROOT).replaceFirst("^www\\.", "");
        String domain = site.domain().toLowerCase(java.util.Locale.ROOT).replaceFirst("^www\\.", "");
        String path = lookup.path().toLowerCase(java.util.Locale.ROOT);
        String prefix = site.path().toLowerCase(java.util.Locale.ROOT);
        return host.equals(domain) && (path + "/").startsWith(prefix);
    }

    @Test
    void generatedScenariosAreRepeatableAndShapedLikeRequests() {
        for (NetworkVariant variant : NetworkVariant.ALL) {
            List<Scenario> first = MultisiteScenarios.generated(variant, "test-seed", 30);
            assertThat(MultisiteScenarios.generated(variant, "test-seed", 30)).isEqualTo(first);
            assertThat(first).extracting(Scenario::id).doesNotHaveDuplicates();
            assertThat(first).allSatisfy(scenario -> {
                assertThat(scenario.generated()).isTrue();
                assertThat(scenario.variant()).isEqualTo(variant);
                long creates = scenario.ops().stream()
                        .filter(ScenarioOp.CreateSite.class::isInstance)
                        .count();
                long lookups = scenario.ops().stream()
                        .filter(ScenarioOp.Lookup.class::isInstance)
                        .count();
                assertThat(creates).isBetween(2L, 5L);
                assertThat(lookups).isBetween(4L, 8L);
                if (variant.subdomainInstall()) {
                    // a subdomain network has no subdirectory sites on its own domain
                    assertThat(scenario.ops())
                            .filteredOn(ScenarioOp.CreateSite.class::isInstance)
                            .noneMatch(
                                    op -> ((ScenarioOp.CreateSite) op).domain().equals(variant.domain()));
                }
            });
        }
    }

    @Test
    void everyVariantRunsHandWrittenAndGeneratedScenarios() {
        List<Scenario> all = MultisiteScenarios.all();
        Set<String> variants =
                all.stream().map(scenario -> scenario.variant().name()).collect(Collectors.toSet());
        assertThat(variants)
                .containsExactlyInAnyOrder("subdirectory", "subdirectory-net", "subdomain", "subdirectory-2");
        assertThat(all)
                .filteredOn(Scenario::generated)
                .hasSize(3 * MultisiteScenarios.GENERATED + MultisiteScenarios.GENERATED_TWO_SEGMENTS);
        assertThat(all).extracting(Scenario::caseId).doesNotHaveDuplicates();
    }
}
