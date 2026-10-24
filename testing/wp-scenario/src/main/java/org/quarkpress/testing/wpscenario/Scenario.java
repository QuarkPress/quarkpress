package org.quarkpress.testing.wpscenario;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Operations run in order on a fresh network of one variant. {@code rule} names the behaviour-reference rule a
 * hand-written scenario checks ({@code generated} for generated ones). {@code revisionsKeep} is WordPress's
 * {@code WP_POST_REVISIONS} for the run, applied on both sides; absent for the default (unlimited).
 */
public record Scenario(
        String id,
        String rule,
        NetworkVariant variant,
        List<ScenarioOp> ops,
        boolean generated,
        OptionalInt revisionsKeep) {

    /** A scenario without a revisions limit. */
    public Scenario(String id, String rule, NetworkVariant variant, List<ScenarioOp> ops, boolean generated) {
        this(id, rule, variant, ops, generated, OptionalInt.empty());
    }

    public Scenario {
        Objects.requireNonNull(revisionsKeep, "revisionsKeep");
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
        requirePostReferences(id, variant, ops);
    }

    /**
     * Post operations run on the subdirectory network, act as users created before them (or user 0) and name posts
     * inserted before them; an insertion counts even when it fails, as a user creation does. Term operations name
     * only terms that may have been created before them ({@code t<k>}).
     */
    private static void requirePostReferences(String id, NetworkVariant variant, List<ScenarioOp> ops) {
        int inserted = 0;
        int mayHaveCreated = 0;
        int users = 0;
        for (int i = 0; i < ops.size(); i++) {
            ScenarioOp op = ops.get(i);
            if (op instanceof ScenarioOp.CreateUser) {
                users++;
            }
            if (!(op instanceof ScenarioOp.PostOp post)) {
                continue;
            }
            String at = "scenario " + id + ": op " + (i + 1);
            if (!variant.equals(NetworkVariant.SUBDIRECTORY)) {
                throw new IllegalArgumentException(
                        at + " is a post operation, which runs on the subdirectory network only");
            }
            for (int user : PostScenarioJson.actors(post)) {
                if (user > users) {
                    throw new IllegalArgumentException(
                            at + " acts as user #" + user + ", but only " + users + " users were created before it");
                }
            }
            for (int user : PostScenarioJson.users(post)) {
                if (user > users) {
                    throw new IllegalArgumentException(
                            at + " refers to user #" + user + ", but only " + users + " users were created before it");
                }
            }
            for (int reference : PostScenarioJson.posts(post)) {
                if (reference > inserted) {
                    throw new IllegalArgumentException(at + " refers to post #" + reference + ", but only " + inserted
                            + " posts were inserted before it");
                }
            }
            if (post instanceof ScenarioOp.TermOp term) {
                for (int k : TermScenarioJson.termRefs(term)) {
                    if (k > mayHaveCreated) {
                        throw new IllegalArgumentException(at + " refers to term t" + k + ", but at most "
                                + mayHaveCreated + " terms were created before it");
                    }
                }
                mayHaveCreated += TermScenarioJson.mayCreate(term);
            }
            if (post instanceof ScenarioOp.InsertPost) {
                inserted++;
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
