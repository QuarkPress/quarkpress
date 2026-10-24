package org.quarkpress.testing.wpscenario;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Normalizes what the PHP driver observed of revision operations. */
final class RevisionOutcomes {

    /** The ids of the revision rows created during the scenario, in creation order. */
    record RevisionContext(List<Long> created) {}

    private RevisionOutcomes() {}

    static RevisionContext context(JsonNode out, String where) {
        List<Long> created = new ArrayList<>();
        JsonNode revisions = out.get("revisions");
        if (revisions == null || !revisions.isArray()) {
            throw new IllegalStateException("the driver output of " + where + " has no 'revisions'");
        }
        revisions.forEach(entry -> created.add(entry.get("id").asLong()));
        return new RevisionContext(created);
    }

    static void result(
            Outcome outcome,
            int op,
            ScenarioOp.RevisionOp revision,
            JsonNode result,
            RevisionContext context,
            Map<Long, Integer> postIndex) {
        String kind = revision.kind();
        if (result.path("missing").asBoolean()) {
            outcome.done(op, kind, "missing");
            return;
        }
        if (result.hasNonNull("error")) {
            outcome.done(op, kind, "refused:" + result.get("error").asText());
            return;
        }
        switch (revision) {
            case ScenarioOp.Autosave autosave ->
                outcome.done(op, kind, ref(result.get("id").asLong(), context, postIndex));
            case ScenarioOp.Restore restore ->
                outcome.done(
                        op,
                        kind,
                        result.path("ok").asBoolean()
                                ? "ok"
                                : result.get("result").asText());
            case ScenarioOp.DeleteRevision delete ->
                outcome.done(op, kind, result.get("result").asText());
            case ScenarioOp.ListRevisions list -> {
                List<String> refs = new ArrayList<>();
                result.get("ids").forEach(id -> refs.add(ref(id.asLong(), context, postIndex)));
                outcome.revisionRefs(op, kind, refs);
            }
            case ScenarioOp.AutosaveOf of -> {
                long id = result.get("id").asLong();
                outcome.done(op, kind, id == 0 ? "none" : ref(id, context, postIndex));
            }
            case ScenarioOp.CountRevisions count -> {
                long latest = result.get("latest").asLong();
                outcome.done(
                        op,
                        kind,
                        "latest=" + (latest == 0 ? "none" : ref(latest, context, postIndex)) + " count="
                                + result.get("count").asLong());
            }
        }
    }

    /** The final records of the revision rows created during the scenario. */
    static void records(
            Outcome outcome,
            JsonNode out,
            RevisionContext context,
            Map<Long, Integer> postIndex,
            Map<Long, Integer> userIndex,
            long installerId,
            RunWindow window) {
        for (JsonNode entry : out.get("revisions")) {
            JsonNode revision = entry.get("revision");
            outcome.observedRevision(
                    "revision#r" + entry.get("index").asInt(),
                    revision.path("deleted").asBoolean()
                            ? ObservedRevision.DELETED
                            : record(revision, context, postIndex, userIndex, installerId, window));
        }
    }

    private static ObservedRevision record(
            JsonNode revision,
            RevisionContext context,
            Map<Long, Integer> postIndex,
            Map<Long, Integer> userIndex,
            long installerId,
            RunWindow window) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", revision.get("id").asLong());
        raw.put("parent", revision.get("parent").asLong());
        raw.put("author", revision.get("author").asLong());
        raw.put("autosave", revision.get("autosave").asBoolean());
        raw.put("title", revision.get("title").asText());
        raw.put("content", revision.get("content").asText());
        raw.put("excerpt", revision.get("excerpt").asText());
        List<String> footnotes = new ArrayList<>();
        revision.get("footnotes").forEach(value -> footnotes.add(value.asText()));
        raw.put("footnotes", footnotes);
        raw.put("date_gmt", revision.get("date_gmt").asText());
        return RevisionRecords.record(raw, context.created(), postIndex, userIndex, installerId, window);
    }

    private static String ref(long id, RevisionContext context, Map<Long, Integer> postIndex) {
        return RevisionRecords.ref(id, context.created(), postIndex);
    }
}
