package org.quarkpress.testing.wpscenario;

import java.util.List;
import java.util.Objects;

/**
 * Operations run in order on a fresh network of one variant. {@code rule} names the behaviour-reference rule a
 * hand-written scenario checks ({@code generated} for generated ones).
 */
public record Scenario(String id, String rule, NetworkVariant variant, List<ScenarioOp> ops, boolean generated) {

    public Scenario {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(variant, "variant");
        ScenarioRules.requireId(id);
        ops = List.copyOf(ops);
        if (ops.isEmpty()) {
            throw new IllegalArgumentException("scenario " + id + " has no ops");
        }
        int created = 0;
        for (int i = 0; i < ops.size(); i++) {
            int site = switch (ops.get(i)) {
                case ScenarioOp.CreateSite create -> -1;
                case ScenarioOp.Lookup lookup -> -1;
                case ScenarioOp.SetFlag flag -> flag.site();
                case ScenarioOp.ReadOptions read -> read.site();
            };
            if (site > created) {
                throw new IllegalArgumentException("scenario " + id + ": op " + (i + 1) + " refers to site #" + site
                        + ", but only " + created + " sites were created before it");
            }
            if (ops.get(i) instanceof ScenarioOp.CreateSite) {
                created++;
            }
        }
    }

    /** The comparison's case id: the scenario on its variant. */
    public String caseId() {
        return id + "@" + variant.name();
    }
}
