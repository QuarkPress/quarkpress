package org.quarkpress.testing.wpscenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.testing.wporacle.OracleJson;

/** The revision operations of the scenario format. */
final class RevisionScenarioJson {

    static final List<String> KINDS =
            List.of("autosave", "restore", "delete_revision", "revisions", "autosave_of", "revision_count");

    private RevisionScenarioJson() {}

    static ScenarioOp.RevisionOp read(String kind, JsonNode op, String id) {
        return switch (kind) {
            case "autosave" -> {
                ScenarioJson.onlyKeys(op, id, "op", "as", "post", "fields", "meta");
                yield new ScenarioOp.Autosave(
                        ScenarioJson.number(op, "as", id),
                        ScenarioJson.number(op, "post", id),
                        fields(op.get("fields"), id),
                        op.has("meta") ? Optional.of(meta(op.get("meta"), id)) : Optional.empty());
            }
            case "restore" -> {
                ScenarioJson.onlyKeys(op, id, "op", "as", "revision", "fields");
                yield new ScenarioOp.Restore(
                        ScenarioJson.number(op, "as", id),
                        ScenarioJson.text(op, "revision", id),
                        ScenarioJson.texts(op, "fields", id));
            }
            case "delete_revision" -> {
                ScenarioJson.onlyKeys(op, id, "op", "revision");
                yield new ScenarioOp.DeleteRevision(ScenarioJson.text(op, "revision", id));
            }
            case "revisions" -> {
                ScenarioJson.onlyKeys(op, id, "op", "as", "post", "args");
                yield new ScenarioOp.ListRevisions(
                        ScenarioJson.number(op, "as", id),
                        ScenarioJson.number(op, "post", id),
                        args(op.get("args"), id));
            }
            case "autosave_of" -> {
                ScenarioJson.onlyKeys(op, id, "op", "as", "post", "user");
                yield new ScenarioOp.AutosaveOf(
                        ScenarioJson.number(op, "as", id),
                        ScenarioJson.number(op, "post", id),
                        ScenarioJson.number(op, "user", id));
            }
            case "revision_count" -> {
                ScenarioJson.onlyKeys(op, id, "op", "as", "post");
                yield new ScenarioOp.CountRevisions(
                        ScenarioJson.number(op, "as", id), ScenarioJson.number(op, "post", id));
            }
            default -> throw new IllegalArgumentException("scenario " + id + ": unknown op '" + kind + "'");
        };
    }

    static void write(ScenarioOp.RevisionOp revision, ObjectNode out) {
        switch (revision) {
            case ScenarioOp.Autosave autosave -> {
                out.put("as", autosave.as()).put("post", autosave.post());
                out.set("fields", fields(autosave.fields()));
                autosave.meta().ifPresent(meta -> {
                    ObjectNode node = out.putObject("meta");
                    meta.forEach(node::put);
                });
            }
            case ScenarioOp.Restore restore -> {
                out.put("as", restore.as()).put("revision", restore.revision());
                ArrayNode fields = out.putArray("fields");
                restore.fields().forEach(fields::add);
            }
            case ScenarioOp.DeleteRevision delete -> out.put("revision", delete.revision());
            case ScenarioOp.ListRevisions list -> {
                out.put("as", list.as()).put("post", list.post());
                out.set("args", args(list.args()));
            }
            case ScenarioOp.AutosaveOf of ->
                out.put("as", of.as()).put("post", of.post()).put("user", of.user());
            case ScenarioOp.CountRevisions count -> out.put("as", count.as()).put("post", count.post());
        }
    }

    /** The user an op acts as (0, no user or no check, is not listed). */
    static List<Integer> actors(ScenarioOp.RevisionOp revision) {
        int as = switch (revision) {
            case ScenarioOp.Autosave autosave -> autosave.as();
            case ScenarioOp.Restore restore -> restore.as();
            case ScenarioOp.DeleteRevision delete -> 0;
            case ScenarioOp.ListRevisions list -> list.as();
            case ScenarioOp.AutosaveOf of -> of.as();
            case ScenarioOp.CountRevisions count -> count.as();
        };
        return as == 0 ? List.of() : List.of(as);
    }

    /** The users an op refers to besides the one it acts as (0, any user, is not listed). */
    static List<Integer> users(ScenarioOp.RevisionOp revision) {
        List<Integer> users = new ArrayList<>();
        switch (revision) {
            case ScenarioOp.AutosaveOf of -> users.add(of.user());
            case ScenarioOp.ListRevisions list -> list.args().author().ifPresent(users::add);
            case ScenarioOp.Autosave autosave -> {}
            case ScenarioOp.Restore restore -> {}
            case ScenarioOp.DeleteRevision delete -> {}
            case ScenarioOp.CountRevisions count -> {}
        }
        users.removeIf(user -> user == 0);
        return users;
    }

    /** The posts an op names. */
    static List<Integer> posts(ScenarioOp.RevisionOp revision) {
        return switch (revision) {
            case ScenarioOp.Autosave autosave -> List.of(autosave.post());
            case ScenarioOp.ListRevisions list -> List.of(list.post());
            case ScenarioOp.AutosaveOf of -> List.of(of.post());
            case ScenarioOp.CountRevisions count -> List.of(count.post());
            case ScenarioOp.Restore restore -> List.of();
            case ScenarioOp.DeleteRevision delete -> List.of();
        };
    }

    private static AutosaveFields fields(JsonNode node, String id) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("scenario " + id + ": 'fields' must be an object");
        }
        ScenarioJson.onlyKeys(node, id, AutosaveFields.KEYS.toArray(String[]::new));
        AutosaveFields.Builder fields = AutosaveFields.builder();
        if (node.has("title")) {
            fields.title(ScenarioJson.text(node, "title", id));
        }
        if (node.has("content")) {
            fields.content(ScenarioJson.text(node, "content", id));
        }
        if (node.has("excerpt")) {
            fields.excerpt(ScenarioJson.text(node, "excerpt", id));
        }
        return fields.build();
    }

    private static ObjectNode fields(AutosaveFields fields) {
        ObjectNode node = OracleJson.MAPPER.createObjectNode();
        fields.title().ifPresent(value -> node.put("title", value));
        fields.content().ifPresent(value -> node.put("content", value));
        fields.excerpt().ifPresent(value -> node.put("excerpt", value));
        return node;
    }

    private static Map<String, String> meta(JsonNode node, String id) {
        if (!node.isObject()) {
            throw new IllegalArgumentException("scenario " + id + ": 'meta' must be an object");
        }
        Map<String, String> meta = new LinkedHashMap<>();
        node.properties().forEach(entry -> {
            if (!entry.getValue().isTextual()) {
                throw new IllegalArgumentException("scenario " + id + ": 'meta' holds only text");
            }
            meta.put(entry.getKey(), entry.getValue().textValue());
        });
        return meta;
    }

    private static RevisionListArgs args(JsonNode node, String id) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("scenario " + id + ": 'args' must be an object");
        }
        ScenarioJson.onlyKeys(node, id, RevisionListArgs.KEYS.toArray(String[]::new));
        RevisionListArgs.Builder args = RevisionListArgs.builder();
        if (node.has("order")) {
            args.order(ScenarioJson.text(node, "order", id));
        }
        if (node.has("number")) {
            args.number(ScenarioJson.number(node, "number", id));
        }
        if (node.has("offset")) {
            args.offset(ScenarioJson.number(node, "offset", id));
        }
        if (node.has("author")) {
            args.author(ScenarioJson.number(node, "author", id));
        }
        if (node.has("autosaves")) {
            args.autosaves(ScenarioJson.text(node, "autosaves", id));
        }
        if (node.has("check_enabled")) {
            args.checkEnabled(ScenarioJson.bool(node, "check_enabled", id));
        }
        return args.build();
    }

    private static ObjectNode args(RevisionListArgs args) {
        ObjectNode node = OracleJson.MAPPER.createObjectNode();
        args.order().ifPresent(value -> node.put("order", value));
        args.number().ifPresent(value -> node.put("number", value));
        args.offset().ifPresent(value -> node.put("offset", value));
        args.author().ifPresent(value -> node.put("author", value));
        args.autosaves().ifPresent(value -> node.put("autosaves", value));
        args.checkEnabled().ifPresent(value -> node.put("check_enabled", value));
        return node;
    }
}
