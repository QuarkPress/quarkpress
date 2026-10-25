package org.quarkpress.testing.wpscenario;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Normalizes what the PHP driver observed of scheduling operations. */
final class SchedulingOutcomes {

    private SchedulingOutcomes() {}

    /**
     * One scheduling result as a fact: {@code missing}, {@code refused:<code>}, {@code ok}/{@code failed} for the
     * publication check and the fixture, and the surviving posts of a purge, named by insertion index in the
     * driver's order.
     */
    static void result(
            Outcome outcome, int op, ScenarioOp.SchedulingOp step, JsonNode result, Map<Long, Integer> postIndex) {
        String kind = step.kind();
        if (result.path("missing").asBoolean()) {
            outcome.done(op, kind, "missing");
            return;
        }
        if (result.hasNonNull("error")) {
            outcome.done(op, kind, "refused:" + result.get("error").asText());
            return;
        }
        switch (step) {
            case ScenarioOp.RunPublishCheck check ->
                outcome.done(op, kind, WordPressOutcomes.okay(result, at(op, kind)));
            case ScenarioOp.ForceDate force -> outcome.done(op, kind, WordPressOutcomes.okay(result, at(op, kind)));
            case ScenarioOp.RunTrashPurge purge -> outcome.done(op, kind, survivors(result, op, kind, postIndex));
            case ScenarioOp.RunAutoDraftPurge purge -> outcome.done(op, kind, survivors(result, op, kind, postIndex));
        }
    }

    /** The purge fact of the surviving posts' references, in the driver's order. */
    static String survivors(List<String> refs) {
        return refs.isEmpty() ? "survivors=(none)" : "survivors=" + String.join(",", refs);
    }

    private static String at(int op, String kind) {
        return "op " + op + " (" + kind + ")";
    }

    private static String survivors(JsonNode result, int op, String kind, Map<Long, Integer> postIndex) {
        JsonNode ids = result.get("survivors");
        if (ids == null || !ids.isArray()) {
            throw new IllegalStateException("the driver output of " + at(op, kind) + " has no 'survivors'");
        }
        List<String> refs = new ArrayList<>();
        ids.forEach(id -> refs.add(PostRecords.postRef(id.asLong(), postIndex)));
        return survivors(refs);
    }
}
