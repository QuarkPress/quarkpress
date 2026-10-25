package org.quarkpress.testing.wpscenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import org.quarkpress.testing.wporacle.OracleJson;

/** The post operations of the scenario format. */
final class PostScenarioJson {

    static final List<String> KINDS = List.of(
            "site_option",
            "front_page",
            "insert_post",
            "update_post",
            "publish_post",
            "trash_post",
            "untrash_post",
            "delete_post",
            "stick",
            "query_posts",
            "page_uri",
            "read_post",
            "update_post_meta");

    private PostScenarioJson() {}

    static ScenarioOp.PostOp read(String kind, JsonNode op, String id) {
        return switch (kind) {
            case "site_option" -> {
                ScenarioJson.onlyKeys(op, id, "op", "name", "value");
                yield new ScenarioOp.SiteOption(ScenarioJson.text(op, "name", id), ScenarioJson.text(op, "value", id));
            }
            case "front_page" -> {
                ScenarioJson.onlyKeys(op, id, "op", "option", "post");
                yield new ScenarioOp.FrontPage(
                        ScenarioJson.text(op, "option", id), ScenarioJson.number(op, "post", id));
            }
            case "insert_post" -> {
                ScenarioJson.onlyKeys(op, id, "op", "as", "fields");
                yield new ScenarioOp.InsertPost(ScenarioJson.number(op, "as", id), fields(op.get("fields"), id));
            }
            case "update_post" -> {
                ScenarioJson.onlyKeys(op, id, "op", "as", "post", "fields", "edit_date");
                yield new ScenarioOp.UpdatePost(
                        ScenarioJson.number(op, "as", id),
                        ScenarioJson.number(op, "post", id),
                        fields(op.get("fields"), id),
                        op.has("edit_date") && ScenarioJson.bool(op, "edit_date", id));
            }
            case "publish_post" -> {
                ScenarioJson.onlyKeys(op, id, "op", "post");
                yield new ScenarioOp.PublishPost(ScenarioJson.number(op, "post", id));
            }
            case "trash_post" -> {
                ScenarioJson.onlyKeys(op, id, "op", "as", "post");
                yield new ScenarioOp.TrashPost(ScenarioJson.number(op, "as", id), ScenarioJson.number(op, "post", id));
            }
            case "untrash_post" -> {
                ScenarioJson.onlyKeys(op, id, "op", "as", "post");
                yield new ScenarioOp.UntrashPost(
                        ScenarioJson.number(op, "as", id), ScenarioJson.number(op, "post", id));
            }
            case "delete_post" -> {
                ScenarioJson.onlyKeys(op, id, "op", "as", "post", "force");
                yield new ScenarioOp.DeletePost(
                        ScenarioJson.number(op, "as", id),
                        ScenarioJson.number(op, "post", id),
                        ScenarioJson.bool(op, "force", id));
            }
            case "stick" -> {
                ScenarioJson.onlyKeys(op, id, "op", "post", "sticky");
                yield new ScenarioOp.Stick(ScenarioJson.number(op, "post", id), ScenarioJson.bool(op, "sticky", id));
            }
            case "query_posts" -> {
                ScenarioJson.onlyKeys(op, id, "op", "as", "args");
                yield new ScenarioOp.QueryPosts(ScenarioJson.number(op, "as", id), args(op.get("args"), id));
            }
            case "page_uri" -> {
                ScenarioJson.onlyKeys(op, id, "op", "post");
                yield new ScenarioOp.PageUri(ScenarioJson.number(op, "post", id));
            }
            case "read_post" -> {
                ScenarioJson.onlyKeys(op, id, "op", "post");
                yield new ScenarioOp.ReadPost(ScenarioJson.number(op, "post", id));
            }
            case "update_post_meta" -> {
                ScenarioJson.onlyKeys(op, id, "op", "as", "post", "key", "value");
                yield new ScenarioOp.UpdatePostMeta(
                        ScenarioJson.number(op, "as", id),
                        ScenarioJson.number(op, "post", id),
                        ScenarioJson.text(op, "key", id),
                        ScenarioJson.text(op, "value", id));
            }
            default -> throw new IllegalArgumentException("scenario " + id + ": unknown op '" + kind + "'");
        };
    }

    static void write(ScenarioOp.PostOp post, ObjectNode out) {
        switch (post) {
            case ScenarioOp.SiteOption option -> out.put("name", option.name()).put("value", option.value());
            case ScenarioOp.FrontPage front -> out.put("option", front.option()).put("post", front.post());
            case ScenarioOp.InsertPost insert -> {
                out.put("as", insert.as());
                out.set("fields", fields(insert.fields()));
            }
            case ScenarioOp.UpdatePost update -> {
                out.put("as", update.as()).put("post", update.post());
                out.set("fields", fields(update.fields()));
                out.put("edit_date", update.editDate());
            }
            case ScenarioOp.PublishPost publish -> out.put("post", publish.post());
            case ScenarioOp.TrashPost trash -> out.put("as", trash.as()).put("post", trash.post());
            case ScenarioOp.UntrashPost untrash -> out.put("as", untrash.as()).put("post", untrash.post());
            case ScenarioOp.DeletePost delete ->
                out.put("as", delete.as()).put("post", delete.post()).put("force", delete.force());
            case ScenarioOp.Stick stick -> out.put("post", stick.post()).put("sticky", stick.sticky());
            case ScenarioOp.QueryPosts query -> {
                out.put("as", query.as());
                out.set("args", args(query.args()));
            }
            case ScenarioOp.PageUri uri -> out.put("post", uri.post());
            case ScenarioOp.ReadPost read -> out.put("post", read.post());
            case ScenarioOp.UpdatePostMeta meta ->
                out.put("as", meta.as())
                        .put("post", meta.post())
                        .put("key", meta.key())
                        .put("value", meta.value());
            case ScenarioOp.TermOp term -> TermScenarioJson.write(term, out);
            case ScenarioOp.RevisionOp revision -> RevisionScenarioJson.write(revision, out);
            case ScenarioOp.SchedulingOp scheduling -> SchedulingScenarioJson.write(scheduling, out);
        }
    }

    /** The user an op acts as (0, WordPress's user 0, is not listed). */
    static List<Integer> actors(ScenarioOp.PostOp post) {
        int as = switch (post) {
            case ScenarioOp.InsertPost insert -> insert.as();
            case ScenarioOp.UpdatePost update -> update.as();
            case ScenarioOp.TrashPost trash -> trash.as();
            case ScenarioOp.UntrashPost untrash -> untrash.as();
            case ScenarioOp.DeletePost delete -> delete.as();
            case ScenarioOp.QueryPosts query -> query.as();
            case ScenarioOp.SiteOption option -> 0;
            case ScenarioOp.FrontPage front -> 0;
            case ScenarioOp.PublishPost publish -> 0;
            case ScenarioOp.Stick stick -> 0;
            case ScenarioOp.PageUri uri -> 0;
            case ScenarioOp.ReadPost read -> 0;
            case ScenarioOp.UpdatePostMeta meta -> meta.as();
            case ScenarioOp.TermOp term ->
                TermScenarioJson.actors(term).isEmpty()
                        ? 0
                        : TermScenarioJson.actors(term).get(0);
            case ScenarioOp.RevisionOp revision ->
                RevisionScenarioJson.actors(revision).isEmpty()
                        ? 0
                        : RevisionScenarioJson.actors(revision).get(0);
            case ScenarioOp.SchedulingOp scheduling -> 0;
        };
        return as == 0 ? List.of() : List.of(as);
    }

    /** The users an op refers to as authors (0, no author, is not listed). */
    static List<Integer> users(ScenarioOp.PostOp post) {
        List<Integer> users = new ArrayList<>();
        switch (post) {
            case ScenarioOp.InsertPost insert -> insert.fields().author().ifPresent(users::add);
            case ScenarioOp.UpdatePost update -> update.fields().author().ifPresent(users::add);
            case ScenarioOp.QueryPosts query -> {
                users.addAll(query.args().authorIn());
                users.addAll(query.args().authorNotIn());
            }
            case ScenarioOp.SiteOption option -> {}
            case ScenarioOp.FrontPage front -> {}
            case ScenarioOp.PublishPost publish -> {}
            case ScenarioOp.TrashPost trash -> {}
            case ScenarioOp.UntrashPost untrash -> {}
            case ScenarioOp.DeletePost delete -> {}
            case ScenarioOp.Stick stick -> {}
            case ScenarioOp.PageUri uri -> {}
            case ScenarioOp.ReadPost read -> {}
            case ScenarioOp.UpdatePostMeta meta -> {}
            case ScenarioOp.TermOp term -> {}
            case ScenarioOp.RevisionOp revision -> users.addAll(RevisionScenarioJson.users(revision));
            case ScenarioOp.SchedulingOp scheduling -> {}
        }
        users.removeIf(user -> user == 0);
        return users;
    }

    /** The posts an op names (0, none, is not listed). */
    static List<Integer> posts(ScenarioOp.PostOp post) {
        List<Integer> posts = new ArrayList<>();
        switch (post) {
            case ScenarioOp.FrontPage front -> posts.add(front.post());
            case ScenarioOp.InsertPost insert -> insert.fields().parent().ifPresent(posts::add);
            case ScenarioOp.UpdatePost update -> {
                posts.add(update.post());
                update.fields().parent().ifPresent(posts::add);
            }
            case ScenarioOp.PublishPost publish -> posts.add(publish.post());
            case ScenarioOp.TrashPost trash -> posts.add(trash.post());
            case ScenarioOp.UntrashPost untrash -> posts.add(untrash.post());
            case ScenarioOp.DeletePost delete -> posts.add(delete.post());
            case ScenarioOp.Stick stick -> posts.add(stick.post());
            case ScenarioOp.QueryPosts query -> {
                query.args().parent().ifPresent(posts::add);
                posts.addAll(query.args().postIn());
                posts.addAll(query.args().postNotIn());
            }
            case ScenarioOp.PageUri uri -> posts.add(uri.post());
            case ScenarioOp.ReadPost read -> posts.add(read.post());
            case ScenarioOp.SiteOption option -> {}
            case ScenarioOp.UpdatePostMeta meta -> posts.add(meta.post());
            case ScenarioOp.TermOp term -> posts.addAll(TermScenarioJson.posts(term));
            case ScenarioOp.RevisionOp revision -> posts.addAll(RevisionScenarioJson.posts(revision));
            case ScenarioOp.SchedulingOp scheduling -> posts.addAll(SchedulingScenarioJson.posts(scheduling));
        }
        posts.removeIf(reference -> reference == 0);
        return posts;
    }

    private static PostFields fields(JsonNode node, String id) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("scenario " + id + ": 'fields' must be an object");
        }
        ScenarioJson.onlyKeys(node, id, PostFields.KEYS.toArray(String[]::new));
        PostFields.Builder fields = PostFields.builder();
        if (node.has("type")) {
            fields.type(ScenarioJson.text(node, "type", id));
        }
        if (node.has("status")) {
            fields.status(ScenarioJson.text(node, "status", id));
        }
        if (node.has("title")) {
            fields.title(ScenarioJson.text(node, "title", id));
        }
        if (node.has("content")) {
            fields.content(ScenarioJson.text(node, "content", id));
        }
        if (node.has("excerpt")) {
            fields.excerpt(ScenarioJson.text(node, "excerpt", id));
        }
        if (node.has("slug")) {
            fields.slug(ScenarioJson.text(node, "slug", id));
        }
        if (node.has("password")) {
            fields.password(ScenarioJson.text(node, "password", id));
        }
        if (node.has("comment_status")) {
            fields.commentStatus(ScenarioJson.text(node, "comment_status", id));
        }
        if (node.has("ping_status")) {
            fields.pingStatus(ScenarioJson.text(node, "ping_status", id));
        }
        if (node.has("date")) {
            fields.date(ScenarioJson.text(node, "date", id));
        }
        if (node.has("date_gmt")) {
            fields.dateGmt(ScenarioJson.text(node, "date_gmt", id));
        }
        if (node.has("parent")) {
            fields.parent(ScenarioJson.number(node, "parent", id));
        }
        if (node.has("author")) {
            fields.author(ScenarioJson.number(node, "author", id));
        }
        if (node.has("menu_order")) {
            fields.menuOrder(ScenarioJson.number(node, "menu_order", id));
        }
        return fields.build();
    }

    private static ObjectNode fields(PostFields fields) {
        ObjectNode node = OracleJson.MAPPER.createObjectNode();
        fields.type().ifPresent(value -> node.put("type", value));
        fields.status().ifPresent(value -> node.put("status", value));
        fields.title().ifPresent(value -> node.put("title", value));
        fields.content().ifPresent(value -> node.put("content", value));
        fields.excerpt().ifPresent(value -> node.put("excerpt", value));
        fields.slug().ifPresent(value -> node.put("slug", value));
        fields.password().ifPresent(value -> node.put("password", value));
        fields.commentStatus().ifPresent(value -> node.put("comment_status", value));
        fields.pingStatus().ifPresent(value -> node.put("ping_status", value));
        fields.date().ifPresent(value -> node.put("date", value));
        fields.dateGmt().ifPresent(value -> node.put("date_gmt", value));
        fields.parent().ifPresent(value -> node.put("parent", value));
        fields.author().ifPresent(value -> node.put("author", value));
        fields.menuOrder().ifPresent(value -> node.put("menu_order", value));
        return node;
    }

    private static QueryArgs args(JsonNode node, String id) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("scenario " + id + ": 'args' must be an object");
        }
        ScenarioJson.onlyKeys(node, id, QueryArgs.KEYS.toArray(String[]::new));
        QueryArgs.Builder args = QueryArgs.builder();
        texts(node, "types", id).forEach(args::types);
        texts(node, "statuses", id).forEach(args::statuses);
        numbers(node, "author_in", id).forEach(args::authorIn);
        numbers(node, "author_not_in", id).forEach(args::authorNotIn);
        if (node.has("parent")) {
            args.parent(ScenarioJson.number(node, "parent", id));
        }
        numbers(node, "post_in", id).forEach(args::postIn);
        numbers(node, "post_not_in", id).forEach(args::postNotIn);
        if (node.has("name")) {
            args.name(ScenarioJson.text(node, "name", id));
        }
        if (node.has("search")) {
            args.search(ScenarioJson.text(node, "search", id));
        }
        texts(node, "orderby", id).forEach(args::orderBy);
        if (node.has("per_page")) {
            args.perPage(ScenarioJson.number(node, "per_page", id));
        }
        if (node.has("page")) {
            args.page(ScenarioJson.number(node, "page", id));
        }
        if (node.has("offset")) {
            args.offset(ScenarioJson.number(node, "offset", id));
        }
        if (node.has("sticky_first")) {
            args.stickyFirst(ScenarioJson.bool(node, "sticky_first", id));
        }
        return args.build();
    }

    private static ObjectNode args(QueryArgs args) {
        ObjectNode node = OracleJson.MAPPER.createObjectNode();
        putTexts(node, "types", args.types());
        putTexts(node, "statuses", args.statuses());
        putNumbers(node, "author_in", args.authorIn());
        putNumbers(node, "author_not_in", args.authorNotIn());
        args.parent().ifPresent(value -> node.put("parent", value));
        putNumbers(node, "post_in", args.postIn());
        putNumbers(node, "post_not_in", args.postNotIn());
        args.name().ifPresent(value -> node.put("name", value));
        args.search().ifPresent(value -> node.put("search", value));
        putTexts(node, "orderby", args.orderBy());
        put(node, "per_page", args.perPage());
        put(node, "page", args.page());
        put(node, "offset", args.offset());
        if (args.stickyFirst()) {
            node.put("sticky_first", true);
        }
        return node;
    }

    private static void put(ObjectNode node, String key, OptionalInt value) {
        value.ifPresent(present -> node.put(key, present));
    }

    private static void putTexts(ObjectNode node, String key, List<String> values) {
        if (!values.isEmpty()) {
            ArrayNode list = node.putArray(key);
            values.forEach(list::add);
        }
    }

    private static void putNumbers(ObjectNode node, String key, List<Integer> values) {
        if (!values.isEmpty()) {
            ArrayNode list = node.putArray(key);
            values.forEach(list::add);
        }
    }

    /** An optional list of text: absent is empty. */
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
