package org.quarkpress.testing.wpscenario;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Normalizes what the PHP driver observed of term operations. */
final class TermOutcomes {

    /** The ids of created terms in creation order, and the default terms' ids by name ({@code default-<taxonomy>}). */
    record TermContext(List<Long> created, Map<Long, String> defaults) {}

    private TermOutcomes() {}

    static TermContext context(JsonNode out, String where) {
        List<Long> created = new ArrayList<>();
        JsonNode terms = out.get("terms");
        if (terms == null || !terms.isArray()) {
            throw new IllegalStateException("the driver output of " + where + " has no 'terms'");
        }
        terms.forEach(entry -> created.add(entry.get("id").asLong()));
        Map<Long, String> defaults = new HashMap<>();
        JsonNode given = out.get("term_defaults");
        if (given == null || !given.isObject()) {
            throw new IllegalStateException("the driver output of " + where + " has no 'term_defaults'");
        }
        given.properties()
                .forEach(entry -> defaults.put(entry.getValue().get("id").asLong(), "default-" + entry.getKey()));
        return new TermContext(created, defaults);
    }

    static void result(Outcome outcome, int op, ScenarioOp.TermOp term, JsonNode result, TermContext context) {
        String kind = term.kind();
        if (result.path("missing").asBoolean()) {
            outcome.done(op, kind, "missing");
            return;
        }
        if (result.hasNonNull("error")) {
            outcome.done(op, kind, "refused:" + result.get("error").asText());
            return;
        }
        switch (term) {
            case ScenarioOp.InsertTerm insert ->
                outcome.termCreated(op, ref(result.get("id").asLong(), context));
            case ScenarioOp.UpdateTerm update -> outcome.done(op, kind, "ok");
            case ScenarioOp.DeleteTerm delete ->
                outcome.done(op, kind, result.get("result").asText());
            case ScenarioOp.SetPostTerms set -> outcome.termRefs(op, kind, refs(result.get("ids"), null, context));
            case ScenarioOp.RemovePostTerms remove ->
                outcome.done(op, kind, "removed=" + result.get("removed").asBoolean());
            case ScenarioOp.PostTerms read ->
                outcome.termRefs(op, kind, refs(result.get("ids"), result.get("counts"), context));
            case ScenarioOp.QueryTerms query -> {
                // Every count is compared, padded or not, so a count that differs mid-scenario shows where it does.
                List<String> refs = refs(result.get("ids"), result.get("counts"), context);
                outcome.termRefs(op, kind, query.args().unordered() ? TermRecords.sortedRefs(refs) : refs);
            }
            case ScenarioOp.GetTerm get -> {
                JsonNode found = result.get("term");
                if (found == null || found.isNull()) {
                    outcome.done(op, kind, "none");
                } else {
                    outcome.observedTerm("op" + op + ":get_term", record(found, context));
                }
            }
            case ScenarioOp.TermExists exists -> {
                long id = result.get("id").asLong();
                outcome.done(op, kind, id == 0 ? "none" : ref(id, context));
            }
        }
    }

    /** The final records of the created terms and of the default terms. */
    static void records(Outcome outcome, JsonNode out, TermContext context) {
        for (JsonNode entry : out.get("terms")) {
            JsonNode term = entry.get("term");
            outcome.observedTerm(
                    "term#t" + entry.get("index").asInt(),
                    term.path("deleted").asBoolean() ? ObservedTerm.DELETED : record(term, context));
        }
        out.get("term_defaults").properties().forEach(entry -> {
            JsonNode term = entry.getValue().get("term");
            outcome.observedTerm(
                    "term#default-" + entry.getKey(),
                    term.path("deleted").asBoolean() ? ObservedTerm.DELETED : record(term, context));
        });
    }

    static ObservedTerm record(JsonNode term, TermContext context) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", term.get("id").asLong());
        raw.put("taxonomy", term.get("taxonomy").asText());
        raw.put("name", term.get("name").asText());
        raw.put("slug", term.get("slug").asText());
        raw.put("description", term.get("description").asText());
        raw.put("parent", term.get("parent").asLong());
        raw.put("count", term.get("count").asLong());
        return TermRecords.record(raw, context.created(), context.defaults());
    }

    private static String ref(long id, TermContext context) {
        return TermRecords.termRef(id, context.created(), context.defaults());
    }

    private static List<String> refs(JsonNode ids, JsonNode counts, TermContext context) {
        List<String> refs = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            String ref = ref(ids.get(i).asLong(), context);
            refs.add(counts == null ? ref : ref + "=" + counts.get(i).asLong());
        }
        return refs;
    }
}
