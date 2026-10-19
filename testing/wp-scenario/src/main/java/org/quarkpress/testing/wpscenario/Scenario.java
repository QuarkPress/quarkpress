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
        int users = 0;
        for (int i = 0; i < ops.size(); i++) {
            ScenarioOp op = ops.get(i);
            Integer site = switch (op) {
                case ScenarioOp.SetFlag flag -> flag.site();
                case ScenarioOp.ReadOptions read -> read.site();
                case ScenarioOp.AddUser add -> add.site();
                case ScenarioOp.RemoveUser remove -> remove.site();
                case ScenarioOp.SetRole set -> set.site();
                case ScenarioOp.AddCap cap -> cap.site();
                case ScenarioOp.SiteSetting setting -> setting.site();
                case ScenarioOp.AddRole role -> role.site();
                case ScenarioOp.RoleCap role -> role.site();
                case ScenarioOp.RemoveRole role -> role.site();
                case ScenarioOp.Can can -> can.site();
                default -> null;
            };
            int user = switch (op) {
                case ScenarioOp.AddUser add -> add.user();
                case ScenarioOp.RemoveUser remove -> remove.user();
                case ScenarioOp.SetRole set -> set.user();
                case ScenarioOp.AddCap cap -> cap.user();
                case ScenarioOp.SuperAdmin admin -> admin.user();
                case ScenarioOp.Can can -> Math.max(can.user(), can.target());
                default -> 0;
            };
            if (site != null && site < 0) {
                throw new IllegalArgumentException(
                        "scenario " + id + ": op " + (i + 1) + " refers to site #" + site + "; sites count from 0");
            }
            if (site != null && site > created) {
                throw new IllegalArgumentException("scenario " + id + ": op " + (i + 1) + " refers to site #" + site
                        + ", but only " + created + " sites were created before it");
            }
            if (user < 1 && opNamesUser(op)) {
                throw new IllegalArgumentException("scenario " + id + ": op " + (i + 1) + " refers to user #" + user
                        + "; users count from 1 (user 0, a logged-out visitor, is only valid in can)");
            }
            if (user > users) {
                throw new IllegalArgumentException("scenario " + id + ": op " + (i + 1) + " refers to user #" + user
                        + ", but only " + users + " users were created before it");
            }
            if (op instanceof ScenarioOp.CreateSite) {
                created++;
            }
            if (op instanceof ScenarioOp.CreateUser) {
                users++;
            }
        }
    }

    /** Whether the op names a user that must exist (every user operation except a capability check). */
    private static boolean opNamesUser(ScenarioOp op) {
        return op instanceof ScenarioOp.AddUser
                || op instanceof ScenarioOp.RemoveUser
                || op instanceof ScenarioOp.SetRole
                || op instanceof ScenarioOp.AddCap
                || op instanceof ScenarioOp.SuperAdmin;
    }

    /** The comparison's case id: the scenario on its variant. */
    public String caseId() {
        return id + "@" + variant.name();
    }
}
