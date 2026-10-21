package org.quarkpress.testing.wpscenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.quarkpress.testing.wporacle.OracleJson;

/** The term operations of the scenario format. */
final class TermScenarioJson {

    static final List<String> KINDS = List.of(
            "insert_term",
            "update_term",
            "delete_term",
            "set_post_terms",
            "remove_post_terms",
            "post_terms",
            "query_terms",
            "get_term",
            "term_exists");

    private TermScenarioJson() {}

    static ScenarioOp.TermOp read(String kind, JsonNode op, String id) {
        return switch (kind) {
            case "insert_term" -> {
                ScenarioJson.onlyKeys(op, id, "op", "as", "taxonomy", "fields");
                yield new ScenarioOp.InsertTerm(
                        ScenarioJson.number(op, "as", id), ScenarioJson.text(op, "taxonomy", id), fields(op, id));
            }
            case "update_term" -> {
                ScenarioJson.onlyKeys(op, id, "op", "as", "term", "taxonomy", "fields");
                yield new ScenarioOp.UpdateTerm(
                        ScenarioJson.number(op, "as", id),
                        ScenarioJson.text(op, "term", id),
                        ScenarioJson.text(op, "taxonomy", id),
                        fields(op, id));
            }
            case "delete_term" -> {
                ScenarioJson.onlyKeys(op, id, "op", "as", "term", "taxonomy");
                yield new ScenarioOp.DeleteTerm(
                        ScenarioJson.number(op, "as", id),
                        ScenarioJson.text(op, "term", id),
                        ScenarioJson.text(op, "taxonomy", id));
            }
            case "set_post_terms" -> {
                ScenarioJson.onlyKeys(op, id, "op", "as", "post", "taxonomy", "terms", "append");
                yield new ScenarioOp.SetPostTerms(
                        ScenarioJson.number(op, "as", id),
                        ScenarioJson.number(op, "post", id),
                        ScenarioJson.text(op, "taxonomy", id),
                        items(op, id),
                        ScenarioJson.bool(op, "append", id));
            }
            case "remove_post_terms" -> {
                ScenarioJson.onlyKeys(op, id, "op", "as", "post", "taxonomy", "terms");
                yield new ScenarioOp.RemovePostTerms(
                        ScenarioJson.number(op, "as", id),
                        ScenarioJson.number(op, "post", id),
                        ScenarioJson.text(op, "taxonomy", id),
                        items(op, id));
            }
            case "post_terms" -> {
                ScenarioJson.onlyKeys(op, id, "op", "post", "taxonomy");
                yield new ScenarioOp.PostTerms(
                        ScenarioJson.number(op, "post", id), ScenarioJson.text(op, "taxonomy", id));
            }
            case "query_terms" -> {
                ScenarioJson.onlyKeys(op, id, "op", "as", "args");
                yield new ScenarioOp.QueryTerms(ScenarioJson.number(op, "as", id), args(op.get("args"), id));
            }
            case "get_term" -> {
                ScenarioJson.onlyKeys(op, id, "op", "term", "taxonomy");
                yield new ScenarioOp.GetTerm(ScenarioJson.text(op, "term", id), ScenarioJson.text(op, "taxonomy", id));
            }
            case "term_exists" -> {
                ScenarioJson.onlyKeys(op, id, "op", "taxonomy", "value", "parent");
                yield new ScenarioOp.TermExists(
                        ScenarioJson.text(op, "taxonomy", id),
                        ScenarioJson.text(op, "value", id),
                        op.has("parent") ? Optional.of(ScenarioJson.text(op, "parent", id)) : Optional.empty());
            }
            default -> throw new IllegalArgumentException("scenario " + id + ": unknown op '" + kind + "'");
        };
    }

    static void write(ScenarioOp.TermOp term, ObjectNode out) {
        switch (term) {
            case ScenarioOp.InsertTerm insert -> {
                out.put("as", insert.as()).put("taxonomy", insert.taxonomy());
                out.set("fields", fields(insert.fields()));
            }
            case ScenarioOp.UpdateTerm update -> {
                out.put("as", update.as()).put("term", update.term()).put("taxonomy", update.taxonomy());
                out.set("fields", fields(update.fields()));
            }
            case ScenarioOp.DeleteTerm delete ->
                out.put("as", delete.as()).put("term", delete.term()).put("taxonomy", delete.taxonomy());
            case ScenarioOp.SetPostTerms set -> {
                out.put("as", set.as()).put("post", set.post()).put("taxonomy", set.taxonomy());
                out.set("terms", items(set.terms()));
                out.put("append", set.append());
            }
            case ScenarioOp.RemovePostTerms remove -> {
                out.put("as", remove.as()).put("post", remove.post()).put("taxonomy", remove.taxonomy());
                out.set("terms", items(remove.terms()));
            }
            case ScenarioOp.PostTerms read -> out.put("post", read.post()).put("taxonomy", read.taxonomy());
            case ScenarioOp.QueryTerms query -> {
                out.put("as", query.as());
                out.set("args", args(query.args()));
            }
            case ScenarioOp.GetTerm get -> out.put("term", get.term()).put("taxonomy", get.taxonomy());
            case ScenarioOp.TermExists exists -> {
                out.put("taxonomy", exists.taxonomy()).put("value", exists.value());
                exists.parent().ifPresent(parent -> out.put("parent", parent));
            }
        }
    }

    /** The user a term op acts as (0, WordPress's user 0, is not listed). */
    static List<Integer> actors(ScenarioOp.TermOp term) {
        int as = switch (term) {
            case ScenarioOp.InsertTerm insert -> insert.as();
            case ScenarioOp.UpdateTerm update -> update.as();
            case ScenarioOp.DeleteTerm delete -> delete.as();
            case ScenarioOp.SetPostTerms set -> set.as();
            case ScenarioOp.RemovePostTerms remove -> remove.as();
            case ScenarioOp.QueryTerms query -> query.as();
            case ScenarioOp.PostTerms read -> 0;
            case ScenarioOp.GetTerm get -> 0;
            case ScenarioOp.TermExists exists -> 0;
        };
        return as == 0 ? List.of() : List.of(as);
    }

    /** The posts a term op names. */
    static List<Integer> posts(ScenarioOp.TermOp term) {
        return switch (term) {
            case ScenarioOp.SetPostTerms set -> List.of(set.post());
            case ScenarioOp.RemovePostTerms remove -> List.of(remove.post());
            case ScenarioOp.PostTerms read -> List.of(read.post());
            case ScenarioOp.QueryTerms query -> query.args().posts();
            default -> List.of();
        };
    }

    /** The k of every {@code t<k>} reference the op makes. */
    static List<Integer> termRefs(ScenarioOp.TermOp term) {
        List<String> refs = new ArrayList<>();
        switch (term) {
            case ScenarioOp.InsertTerm insert -> insert.fields().parent().ifPresent(refs::add);
            case ScenarioOp.UpdateTerm update -> {
                refs.add(update.term());
                update.fields().parent().ifPresent(refs::add);
            }
            case ScenarioOp.DeleteTerm delete -> refs.add(delete.term());
            case ScenarioOp.SetPostTerms set ->
                set.terms().forEach(item -> item.term().ifPresent(refs::add));
            case ScenarioOp.RemovePostTerms remove ->
                remove.terms().forEach(item -> item.term().ifPresent(refs::add));
            case ScenarioOp.PostTerms read -> {}
            case ScenarioOp.QueryTerms query -> {
                refs.addAll(query.args().include());
                refs.addAll(query.args().exclude());
                refs.addAll(query.args().excludeTree());
                query.args().parent().ifPresent(refs::add);
                query.args().childOf().ifPresent(refs::add);
            }
            case ScenarioOp.GetTerm get -> refs.add(get.term());
            case ScenarioOp.TermExists exists -> exists.parent().ifPresent(refs::add);
        }
        return refs.stream().map(ScenarioRules::termIndex).filter(k -> k > 0).toList();
    }

    /** How many terms the op may create at most: one for an insert, one per name of an assignment. */
    static int mayCreate(ScenarioOp.TermOp term) {
        return switch (term) {
            case ScenarioOp.InsertTerm insert -> 1;
            case ScenarioOp.SetPostTerms set ->
                (int) set.terms().stream()
                        .filter(item -> item.name().isPresent())
                        .count();
            default -> 0;
        };
    }

    private static TermFields fields(JsonNode op, String id) {
        JsonNode node = op.get("fields");
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("scenario " + id + ": 'fields' must be an object");
        }
        ScenarioJson.onlyKeys(node, id, TermFields.KEYS.toArray(String[]::new));
        TermFields.Builder fields = TermFields.builder();
        if (node.has("name")) {
            fields.name(ScenarioJson.text(node, "name", id));
        }
        if (node.has("slug")) {
            fields.slug(ScenarioJson.text(node, "slug", id));
        }
        if (node.has("description")) {
            fields.description(ScenarioJson.text(node, "description", id));
        }
        if (node.has("parent")) {
            fields.parent(ScenarioJson.text(node, "parent", id));
        }
        return fields.build();
    }

    private static ObjectNode fields(TermFields fields) {
        ObjectNode node = OracleJson.MAPPER.createObjectNode();
        fields.name().ifPresent(value -> node.put("name", value));
        fields.slug().ifPresent(value -> node.put("slug", value));
        fields.description().ifPresent(value -> node.put("description", value));
        fields.parent().ifPresent(value -> node.put("parent", value));
        return node;
    }

    private static List<TermItem> items(JsonNode op, String id) {
        JsonNode list = op.get("terms");
        if (list == null || !list.isArray()) {
            throw new IllegalArgumentException("scenario " + id + ": 'terms' must be a list");
        }
        List<TermItem> items = new ArrayList<>();
        for (JsonNode item : list) {
            if (!item.isObject()) {
                throw new IllegalArgumentException("scenario " + id + ": a term item is an object");
            }
            ScenarioJson.onlyKeys(item, id, "term", "name");
            items.add(new TermItem(
                    item.has("term") ? Optional.of(ScenarioJson.text(item, "term", id)) : Optional.empty(),
                    item.has("name") ? Optional.of(ScenarioJson.text(item, "name", id)) : Optional.empty()));
        }
        return items;
    }

    private static ArrayNode items(List<TermItem> items) {
        ArrayNode list = OracleJson.MAPPER.createArrayNode();
        for (TermItem item : items) {
            ObjectNode node = list.addObject();
            item.term().ifPresent(ref -> node.put("term", ref));
            item.name().ifPresent(name -> node.put("name", name));
        }
        return list;
    }

    private static TermQueryArgs args(JsonNode node, String id) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("scenario " + id + ": 'args' must be an object");
        }
        ScenarioJson.onlyKeys(node, id, TermQueryArgs.KEYS.toArray(String[]::new));
        TermQueryArgs.Builder args = TermQueryArgs.builder()
                .taxonomies(texts(node, "taxonomies", id))
                .include(texts(node, "include", id))
                .exclude(texts(node, "exclude", id))
                .excludeTree(texts(node, "exclude_tree", id))
                .slugs(texts(node, "slugs", id))
                .names(texts(node, "names", id))
                .posts(numbers(node, "posts", id));
        if (node.has("orderby")) {
            args.orderBy(ScenarioJson.text(node, "orderby", id));
        }
        if (node.has("order")) {
            args.order(ScenarioJson.text(node, "order", id));
        }
        if (node.has("hide_empty")) {
            args.hideEmpty(ScenarioJson.bool(node, "hide_empty", id));
        }
        if (node.has("hierarchical")) {
            args.hierarchical(ScenarioJson.bool(node, "hierarchical", id));
        }
        if (node.has("pad_counts")) {
            args.padCounts(ScenarioJson.bool(node, "pad_counts", id));
        }
        if (node.has("get_all")) {
            args.all(ScenarioJson.bool(node, "get_all", id));
        }
        if (node.has("childless")) {
            args.childless(ScenarioJson.bool(node, "childless", id));
        }
        if (node.has("search")) {
            args.search(ScenarioJson.text(node, "search", id));
        }
        if (node.has("name_like")) {
            args.nameLike(ScenarioJson.text(node, "name_like", id));
        }
        if (node.has("description_like")) {
            args.descriptionLike(ScenarioJson.text(node, "description_like", id));
        }
        if (node.has("parent")) {
            args.parent(ScenarioJson.text(node, "parent", id));
        }
        if (node.has("child_of")) {
            args.childOf(ScenarioJson.text(node, "child_of", id));
        }
        if (node.has("number")) {
            args.number(ScenarioJson.number(node, "number", id));
        }
        if (node.has("offset")) {
            args.offset(ScenarioJson.number(node, "offset", id));
        }
        return args.build();
    }

    private static ObjectNode args(TermQueryArgs args) {
        ObjectNode node = OracleJson.MAPPER.createObjectNode();
        putTexts(node, "taxonomies", args.taxonomies());
        args.orderBy().ifPresent(value -> node.put("orderby", value));
        args.order().ifPresent(value -> node.put("order", value));
        args.hideEmpty().ifPresent(value -> node.put("hide_empty", value));
        args.hierarchical().ifPresent(value -> node.put("hierarchical", value));
        if (args.padCounts()) {
            node.put("pad_counts", true);
        }
        if (args.all()) {
            node.put("get_all", true);
        }
        putTexts(node, "include", args.include());
        putTexts(node, "exclude", args.exclude());
        putTexts(node, "exclude_tree", args.excludeTree());
        putTexts(node, "slugs", args.slugs());
        putTexts(node, "names", args.names());
        args.search().ifPresent(value -> node.put("search", value));
        args.nameLike().ifPresent(value -> node.put("name_like", value));
        args.descriptionLike().ifPresent(value -> node.put("description_like", value));
        args.parent().ifPresent(value -> node.put("parent", value));
        args.childOf().ifPresent(value -> node.put("child_of", value));
        if (args.childless()) {
            node.put("childless", true);
        }
        if (!args.posts().isEmpty()) {
            ArrayNode posts = node.putArray("posts");
            args.posts().forEach(posts::add);
        }
        args.number().ifPresent(value -> node.put("number", value));
        args.offset().ifPresent(value -> node.put("offset", value));
        return node;
    }

    private static void putTexts(ObjectNode node, String key, List<String> values) {
        if (!values.isEmpty()) {
            ArrayNode list = node.putArray(key);
            values.forEach(list::add);
        }
    }

    private static List<String> texts(JsonNode node, String key, String id) {
        return node.has(key) ? ScenarioJson.texts(node, key, id) : List.of();
    }

    private static List<Integer> numbers(JsonNode node, String key, String id) {
        List<Integer> values = new ArrayList<>();
        JsonNode list = node.get(key);
        if (list == null) {
            return values;
        }
        if (!list.isArray()) {
            throw new IllegalArgumentException("scenario " + id + ": '" + key + "' must be a list");
        }
        for (JsonNode element : list) {
            if (!element.isInt()) {
                throw new IllegalArgumentException("scenario " + id + ": '" + key + "' holds only whole numbers");
            }
            values.add(element.intValue());
        }
        return values;
    }
}
