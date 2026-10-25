package org.quarkpress.testing.wpscenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * The scheduling operations of the scenario format: {@code run_publish_check {post}}, {@code run_trash_purge {}},
 * {@code run_auto_draft_purge {}} and the fixture {@code force_date {post, gmt}} ({@code gmt} a {@code Y-m-d H:i:s}
 * UTC text away from the run).
 */
final class SchedulingScenarioJson {

    static final List<String> KINDS =
            List.of("run_publish_check", "run_trash_purge", "run_auto_draft_purge", "force_date");

    private SchedulingScenarioJson() {}

    static ScenarioOp.SchedulingOp read(String kind, JsonNode op, String id) {
        return switch (kind) {
            case "run_publish_check" -> {
                ScenarioJson.onlyKeys(op, id, "op", "post");
                yield new ScenarioOp.RunPublishCheck(ScenarioJson.number(op, "post", id));
            }
            case "run_trash_purge" -> {
                ScenarioJson.onlyKeys(op, id, "op");
                yield new ScenarioOp.RunTrashPurge();
            }
            case "run_auto_draft_purge" -> {
                ScenarioJson.onlyKeys(op, id, "op");
                yield new ScenarioOp.RunAutoDraftPurge();
            }
            case "force_date" -> {
                ScenarioJson.onlyKeys(op, id, "op", "post", "gmt");
                yield new ScenarioOp.ForceDate(ScenarioJson.number(op, "post", id), ScenarioJson.text(op, "gmt", id));
            }
            default -> throw new IllegalArgumentException("scenario " + id + ": unknown op '" + kind + "'");
        };
    }

    static void write(ScenarioOp.SchedulingOp scheduling, ObjectNode out) {
        switch (scheduling) {
            case ScenarioOp.RunPublishCheck check -> out.put("post", check.post());
            case ScenarioOp.RunTrashPurge purge -> {}
            case ScenarioOp.RunAutoDraftPurge purge -> {}
            case ScenarioOp.ForceDate force -> out.put("post", force.post()).put("gmt", force.gmt());
        }
    }

    /** The post indexes an op names; the purges name none. */
    static List<Integer> posts(ScenarioOp.SchedulingOp scheduling) {
        return switch (scheduling) {
            case ScenarioOp.RunPublishCheck check -> List.of(check.post());
            case ScenarioOp.ForceDate force -> List.of(force.post());
            case ScenarioOp.RunTrashPurge purge -> List.of();
            case ScenarioOp.RunAutoDraftPurge purge -> List.of();
        };
    }
}
