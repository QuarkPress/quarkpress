package org.quarkpress.testing.wpscenario;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.quarkpress.core.OptionStore;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.core.SiteRequest;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.multisite.NewSite;
import org.quarkpress.multisite.SiteChanges;
import org.quarkpress.multisite.SiteFlag;
import org.quarkpress.multisite.Sites;
import org.quarkpress.multisite.runtime.HttpResolution;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.testing.wporacle.ParityRun;
import org.quarkpress.testing.wporacle.ParitySkipList;

/**
 * Runs scenarios against a real WordPress (the PHP driver on the MySQL container) and against QuarkPress (this test
 * application's {@code Sites} and HTTP resolution), and compares what each observed.
 */
abstract class ScenarioParityBase {

    static final SiteId MAIN = SiteId.of(1);
    static final String MAIN_DOMAIN = "localhost";
    static final String MAIN_PATH = "/";

    @Inject
    Sites sites;

    @Inject
    HttpResolution resolution;

    @Inject
    SiteScope scope;

    @Inject
    OptionStore store;

    /** Compares every scenario; fails on a difference, a stale skip entry, or fewer comparisons than {@code minimum}. */
    void compareWithWordPress(String library, List<Scenario> scenarios, Path skips, int minimum) throws Exception {
        assumeTrue(ScenarioDatabase.runtimeAvailable(), "ASSUMPTION: no container runtime");
        Path wordpress = WordPressTree.ensure(System.getProperty("wp.version"));
        ParityRun run =
                new ParityRun(library, ParitySkipList.load(skips), Path.of("target", library + "-failures.json"));
        try (ScenarioDatabase database = ScenarioDatabase.start()) {
            WordPressScenarioOracle oracle = WordPressScenarioOracle.create(wordpress, database);
            for (Scenario scenario : scenarios) {
                Outcome wordpressSide = WordPressOutcomes.normalize(scenario, oracle.run(scenario));
                Outcome quarkpressSide = runOnQuarkPress(scenario);
                if (scenario.generated()) {
                    run.generated(scenario.caseId(), ScenarioJson.write(scenario));
                }
                ScenarioComparison.compare(run, scenario.caseId(), wordpressSide, quarkpressSide);
            }
        }
        System.out.println(library + ": " + scenarios.size() + " scenarios, " + run.comparisons() + " comparisons, "
                + run.mismatches().size() + " differences");
        run.assertParity(minimum);
    }

    /** Runs one scenario on QuarkPress's network moved to the scenario's address, then removes what it created. */
    Outcome runOnQuarkPress(Scenario scenario) throws Exception {
        NetworkVariant variant = scenario.variant();
        Outcome outcome = new Outcome();
        List<SiteId> created = new ArrayList<>();
        sites.update(MAIN, SiteChanges.none().withDomain(variant.domain()).withPath(variant.path()));
        try {
            int op = 0;
            for (ScenarioOp step : scenario.ops()) {
                op++;
                switch (step) {
                    case ScenarioOp.CreateSite create -> {
                        try {
                            SiteInfo site = sites.create(NewSite.of(create.domain(), create.path(), create.title()));
                            created.add(site.id());
                            outcome.created(op, created.size());
                        } catch (IllegalArgumentException refused) {
                            created.add(null);
                            outcome.refused(op, Refusals.ofQuarkPress(refused.getMessage()));
                        }
                    }
                    case ScenarioOp.Lookup lookup -> {
                        Binding binding = resolution
                                .route(new SiteRequest("http", lookup.host(), -1, lookup.path()))
                                .binding();
                        outcome.lookedUp(op, nameOf(binding, created));
                    }
                    case ScenarioOp.SetFlag flag -> {
                        SiteId id = created.get(flag.site() - 1);
                        if (id == null) {
                            outcome.flagSet(op, "missing");
                        } else {
                            if (flag.flag() == ScenarioOp.Flag.PUBLIC) {
                                sites.setPublic(id, flag.value() ? 1 : 0);
                            } else {
                                sites.setFlag(id, SiteFlag.valueOf(flag.flag().name()), flag.value());
                            }
                            outcome.flagSet(op, "ok");
                        }
                    }
                    case ScenarioOp.ReadOptions read -> {
                        SiteId id = read.site() == 0 ? MAIN : created.get(read.site() - 1);
                        for (String name : read.names()) {
                            if (id == null) {
                                outcome.option(op, name, null);
                                continue;
                            }
                            Optional<JsonNode> value = scope.call(id, () -> store.find(id, name));
                            String text = value.map(node -> node.isTextual() ? node.textValue() : node.toString())
                                    .orElse(null);
                            if (text != null && name.equals("blogname")) {
                                text = Outcome.title(text, id.value(), read.site());
                            }
                            outcome.option(op, name, text);
                        }
                    }
                }
            }
            for (int index = 1; index <= created.size(); index++) {
                SiteId id = created.get(index - 1);
                if (id == null) {
                    continue;
                }
                SiteInfo site = sites.find(id).orElseThrow();
                outcome.site(
                        index,
                        site.domain(),
                        site.path(),
                        site.publicFlag(),
                        site.archived(),
                        site.mature(),
                        site.spam(),
                        site.deleted());
            }
        } finally {
            for (int index = created.size() - 1; index >= 0; index--) {
                if (created.get(index) != null) {
                    sites.delete(created.get(index));
                }
            }
            sites.update(MAIN, SiteChanges.none().withDomain(MAIN_DOMAIN).withPath(MAIN_PATH));
        }
        return outcome;
    }

    private static String nameOf(Binding binding, List<SiteId> created) {
        if (binding.kind() != Binding.Kind.SITE) {
            return Outcome.NONE;
        }
        if (binding.site().equals(MAIN)) {
            return Outcome.siteName(0);
        }
        int index = created.indexOf(binding.site());
        return index < 0 ? "#?" + binding.site().value() : Outcome.siteName(index + 1);
    }
}
