package org.quarkpress.testing.wpscenario;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalizes what the PHP driver observed. The driver's output for a scenario is
 * {@code {"main": <blog id of the main site>, "results": [...], "sites": [...]}} with one result per operation:
 * <ul>
 *   <li>{@code create_site}: {@code {"id": <blog id>}} or {@code {"error": <wp_insert_site error code, or
 *       "reserved">}};
 *   <li>{@code lookup}: {@code {"result": "site", "id": <blog id>}}, {@code {"result": "redirect", "to": <url>}} or
 *       {@code {"result": "not_installed"}} (what {@code ms_load_current_site_and_network} answered);
 *   <li>{@code set_flag}: {@code {"ok": <bool>}} or {@code {"missing": true}};
 *   <li>{@code read_options}: {@code {"values": {<name>: <text or null>}}} or {@code {"missing": true}};
 * </ul>
 * and {@code sites} lists each created site's final record ({@code index}, {@code id}, {@code domain}, {@code path},
 * {@code public}, {@code archived}, {@code mature}, {@code spam}, {@code deleted}) in creation order. Domains and paths
 * are lower-cased (QuarkPress stores them so, WordPress compares them through its collation), and a redirect or "not
 * installed" becomes "no site" (QuarkPress serves such requests without a site): both are documented differences,
 * deliberate ones: the redirect target and the distinction between the two are not compared. A default title
 * {@code Site <id>} is shown as {@code Site #<index>} only for a site created with an empty title. Output that does
 * not have the shape above fails with an {@link IllegalStateException} naming the case.
 */
public final class WordPressOutcomes {

    private WordPressOutcomes() {}

    public static Outcome normalize(Scenario scenario, JsonNode out) {
        String where = scenario.caseId();
        long main = field(out, "main", where).asLong();
        Map<Long, Integer> indexById = new HashMap<>();
        Map<Integer, Long> idByIndex = new HashMap<>();
        indexById.put(main, 0);
        idByIndex.put(0, main);
        JsonNode results = field(out, "results", where);
        List<ScenarioOp> ops = scenario.ops();
        if (!results.isArray() || results.size() != ops.size()) {
            throw new IllegalStateException("the driver reported " + (results.isArray() ? results.size() : 0)
                    + " results for the " + ops.size() + " ops of " + where);
        }
        Outcome outcome = new Outcome();
        List<Boolean> emptyTitles = new ArrayList<>();
        emptyTitles.add(false);
        int creates = 0;
        for (int i = 0; i < ops.size(); i++) {
            int op = i + 1;
            String at = where + " op " + op;
            JsonNode result = results.get(i);
            switch (ops.get(i)) {
                case ScenarioOp.CreateSite create -> {
                    creates++;
                    emptyTitles.add(create.title().isEmpty());
                    if (result.has("id")) {
                        long id = result.get("id").asLong();
                        indexById.put(id, creates);
                        idByIndex.put(creates, id);
                        outcome.created(op, creates);
                    } else {
                        outcome.refused(
                                op,
                                Refusals.ofWordPress(field(result, "error", at).asText()));
                    }
                }
                case ScenarioOp.Lookup lookup ->
                    outcome.lookedUp(
                            op,
                            "site".equals(field(result, "result", at).asText())
                                    ? nameOf(indexById, field(result, "id", at).asLong())
                                    : Outcome.NONE);
                case ScenarioOp.SetFlag flag ->
                    outcome.flagSet(
                            op,
                            result.path("missing").asBoolean()
                                    ? "missing"
                                    : field(result, "ok", at).asBoolean() ? "ok" : "failed");
                case ScenarioOp.ReadOptions read -> {
                    boolean missing = result.path("missing").asBoolean();
                    JsonNode values = missing ? null : field(result, "values", at);
                    Long id = idByIndex.get(read.site());
                    for (String name : read.names()) {
                        JsonNode value = missing ? null : values.get(name);
                        String text = value == null || value.isNull() ? null : value.asText();
                        if (text != null && id != null && name.equals("blogname") && emptyTitles.get(read.site())) {
                            text = Outcome.title(text, id, read.site());
                        }
                        outcome.option(op, name, text);
                    }
                }
            }
        }
        for (JsonNode site : field(out, "sites", where)) {
            String at = where + " site";
            outcome.site(
                    field(site, "index", at).asInt(),
                    ScenarioRules.lowerAscii(field(site, "domain", at).asText()),
                    ScenarioRules.lowerAscii(field(site, "path", at).asText()),
                    field(site, "public", at).asInt(),
                    field(site, "archived", at).asInt() != 0,
                    field(site, "mature", at).asInt() != 0,
                    field(site, "spam", at).asInt() != 0,
                    field(site, "deleted", at).asInt() != 0);
        }
        return outcome;
    }

    private static JsonNode field(JsonNode node, String name, String where) {
        JsonNode value = node == null ? null : node.get(name);
        if (value == null || value.isNull()) {
            throw new IllegalStateException("the driver output of " + where + " has no '" + name + "'");
        }
        return value;
    }

    private static String nameOf(Map<Long, Integer> indexById, long id) {
        Integer index = indexById.get(id);
        return index == null ? "#?" + id : Outcome.siteName(index);
    }
}
