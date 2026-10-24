package org.quarkpress.testing.wpscenario;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
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
 *   <li>{@code create_user}: {@code {"id": <user id>, "nicename": <text>, "display_name": <text>}} or
 *       {@code {"error": <wp_insert_user error code>}};
 *   <li>{@code can}: {@code {"can": <bool>}}; {@code super_admin} and {@code add_role}: {@code {"changed": <bool>}};
 *       {@code add_user}, {@code remove_user}, {@code set_role}, {@code add_cap}, {@code network_flag},
 *       {@code site_setting}, {@code role_cap} and {@code remove_role}: {@code {"ok": <bool>}}, with {@code "error":
 *       <code>} added when WordPress answered with an error; any of them on a site or user that was not created:
 *       {@code {"missing": true}};
 *   <li>post operations: {@code insert_post} {@code {"id"}}; {@code update_post}, {@code publish_post} and
 *       {@code stick} {@code {"ok": <bool>}}; {@code site_option} and {@code front_page} {@code {"changed": <bool>}}
 *       (what {@code update_option} answered); {@code trash_post}, {@code untrash_post} and {@code delete_post}
 *       {@code {"result": "done:<status before>" | "nothing"}}; {@code query_posts} {@code {"ids", "found"}};
 *       {@code page_uri} {@code {"uri"}}; {@code read_post} {@code {"post": <record>}}; any of them
 *       {@code {"error": <code>}} when WordPress answered with an error, or {@code {"missing": true}} when it names a
 *       post or user that was never created;
 * </ul>
 * and {@code sites} lists each created site's final record ({@code index}, {@code id}, {@code domain}, {@code path},
 * {@code public}, {@code archived}, {@code mature}, {@code spam}, {@code deleted}) in creation order. Domains and paths
 * are lower-cased (QuarkPress stores them so, WordPress compares them through its collation), and a redirect or "not
 * installed" becomes "no site" (QuarkPress serves such requests without a site): both are documented differences,
 * deliberate ones: the redirect target and the distinction between the two are not compared. A default title
 * {@code Site <id>} is shown as {@code Site #<index>} only for a site created with an empty title. Output that does
 * not have the shape above fails with an {@link IllegalStateException} naming the case.
 *
 * <p>A scenario with post operations also reads {@code started} and {@code finished} (Unix seconds), {@code home} (the
 * main site's home URL), {@code installer} (the installation's user id) and {@code posts}, the final record of each
 * inserted post ({@code [{"index", "post"}]}). A record holds {@code id, type, status, slug, title, content, excerpt,
 * author, parent, menu_order, date, date_gmt, modified, modified_gmt, comment_status, ping_status, password, sticky,
 * guid, meta}, or {@code {"deleted": true}}. Users and posts are named by index. A stamp of the run shows as
 * {@code now}: a local date or modification within 14 hours of the run, whatever the zone when it was written, and a
 * GMT one within the run; each local date's offset from its GMT date is compared in minutes, so a zone slip still
 * shows. Id-shaped slugs, page paths and slug meta name their post ({@code #self}, {@code #p<k>});
 * {@code page_on_front} and {@code page_for_posts} name their post; a query ordered by {@code none} is a sorted set.
 *
 * <p>Term operations answer {@code insert_term} {@code {"id"}}; {@code update_term} {@code {"ok"}};
 * {@code delete_term} {@code {"result": "deleted" | "nothing" | "kept"}} (true, false, or 0 for a default term);
 * {@code set_post_terms} {@code {"ids"}}; {@code remove_post_terms} {@code {"removed"}}; {@code post_terms} and
 * {@code query_terms} {@code {"ids", "counts"}} (compared as {@code <ref>=<count>}); {@code get_term}
 * {@code {"term": <record> | null}};
 * {@code term_exists} {@code {"id"}} (0 for none); any of them {@code {"error": <code>}} or {@code {"missing": true}}
 * as post operations do. A scenario with term operations also reads {@code terms}, the created terms in creation
 * order ({@code [{"index", "id", "term"}]}, deleted ones as {@code {"deleted": true}}), and {@code term_defaults}
 * ({@code {"category": {"id", "term"}, "genre": {"id", "term"}}}). A term record holds {@code id, taxonomy, name,
 * slug, description, parent, count}. Terms are named by creation order ({@code t<k>}) or as a default term
 * ({@code default-category}, {@code default-genre}); a slug that is the term's own id, with any {@code -<digits>}
 * suffixes, shows as {@code #self}.
 *
 * <p>{@code update_post_meta} answers {@code {"changed"}}. Revision operations answer {@code autosave} {@code {"id",
 * "parent"}}; {@code restore} {@code {"ok": true}} or {@code {"result": "nothing"}}; {@code delete_revision}
 * {@code {"result": "deleted" | "nothing"}}; {@code revisions} {@code {"ids"}}; {@code autosave_of} {@code {"id"}}
 * (0 for none); {@code revision_count} {@code {"latest", "count"}} (latest 0 for none); any of them
 * {@code {"error": <code>}} or {@code {"missing": true}} as post operations do. A scenario with revision operations
 * (or {@code update_post_meta}) also reads {@code revisions}, the revision rows created during the scenario in
 * creation order ({@code [{"index", "id", "revision"}]}, deleted ones as {@code {"deleted": true}}). A revision record
 * holds {@code id, parent, author, autosave, title, content, excerpt, footnotes} (a list of texts) and
 * {@code date_gmt}. Revision rows are named {@code r<k>} by creation order.
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
        Map<Long, Integer> userIndex = new HashMap<>();
        Map<Long, Integer> postIndex = new HashMap<>();
        boolean posts = ops.stream().anyMatch(ScenarioOp.PostOp.class::isInstance);
        PostContext context = posts
                ? new PostContext(
                        userIndex,
                        postIndex,
                        field(out, "installer", where).asLong(),
                        field(out, "home", where).asText(),
                        new RunWindow(
                                Instant.ofEpochSecond(
                                        field(out, "started", where).asLong()),
                                Instant.ofEpochSecond(
                                        field(out, "finished", where).asLong())),
                        PostRecords.includeGuid(scenario))
                : null;
        TermOutcomes.TermContext terms =
                ops.stream().anyMatch(ScenarioOp.TermOp.class::isInstance) ? TermOutcomes.context(out, where) : null;
        RevisionOutcomes.RevisionContext revisions = ops.stream()
                        .anyMatch(op -> op instanceof ScenarioOp.RevisionOp || op instanceof ScenarioOp.UpdatePostMeta)
                ? RevisionOutcomes.context(out, where)
                : null;
        int inserted = 0;
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
                        if (text != null && posts) {
                            text = PostRecords.optionText(name, text, postIndex);
                        }
                        outcome.option(op, name, text);
                    }
                }
                case ScenarioOp.CreateUser user -> {
                    // users are numbered by creation order, failed creations included
                    long key = result.has("id") ? result.get("id").asLong() : -1L - userIndex.size();
                    userIndex.put(key, userIndex.size() + 1);
                    if (result.has("id")) {
                        outcome.userCreated(
                                op,
                                field(result, "nicename", at).asText(),
                                field(result, "display_name", at).asText());
                    } else {
                        outcome.userRefused(op, field(result, "error", at).asText());
                    }
                }
                case ScenarioOp.Can can -> {
                    if (result.path("missing").asBoolean()) {
                        outcome.done(op, can.kind(), "missing");
                    } else {
                        outcome.answer(op, field(result, "can", at).asBoolean());
                    }
                }
                case ScenarioOp.SuperAdmin admin -> outcome.done(op, admin.kind(), changed(result, at));
                case ScenarioOp.AddRole role -> outcome.done(op, role.kind(), changed(result, at));
                case ScenarioOp.AddUser add -> outcome.done(op, add.kind(), okay(result, at));
                case ScenarioOp.RemoveUser remove -> outcome.done(op, remove.kind(), okay(result, at));
                case ScenarioOp.SetRole set -> outcome.done(op, set.kind(), okay(result, at));
                case ScenarioOp.AddCap cap -> outcome.done(op, cap.kind(), okay(result, at));
                case ScenarioOp.NetworkFlag flag -> outcome.done(op, flag.kind(), okay(result, at));
                case ScenarioOp.SiteSetting setting -> outcome.done(op, setting.kind(), okay(result, at));
                case ScenarioOp.RoleCap role -> outcome.done(op, role.kind(), okay(result, at));
                case ScenarioOp.RemoveRole role -> outcome.done(op, role.kind(), okay(result, at));
                case ScenarioOp.PostOp post -> {
                    if (post instanceof ScenarioOp.InsertPost) {
                        inserted++;
                        if (result.has("id")) {
                            postIndex.put(result.get("id").asLong(), inserted);
                        }
                    }
                    postResult(outcome, op, post, result, at, context, terms, revisions);
                }
            }
        }
        for (JsonNode site : field(out, "sites", where)) {
            String at = where + " site";
            int index = field(site, "index", at).asInt();
            long reported = field(site, "id", at).asLong();
            Long expected = idByIndex.get(index);
            if (index < 1 || expected == null || expected != reported) {
                throw new IllegalStateException("the driver reported site #" + index + " of " + where + " with id "
                        + reported + ", but the create_site results gave "
                        + (expected == null ? "no such site" : "id " + expected));
            }
            outcome.site(
                    index,
                    ScenarioRules.lowerAscii(field(site, "domain", at).asText()),
                    ScenarioRules.lowerAscii(field(site, "path", at).asText()),
                    field(site, "public", at).asInt(),
                    field(site, "archived", at).asInt() != 0,
                    field(site, "mature", at).asInt() != 0,
                    field(site, "spam", at).asInt() != 0,
                    field(site, "deleted", at).asInt() != 0);
        }
        if (posts) {
            for (JsonNode entry : field(out, "posts", where)) {
                String at = where + " post";
                outcome.observed(
                        "post#" + field(entry, "index", at).asInt(), record(field(entry, "post", at), context, at));
            }
        }
        if (terms != null) {
            TermOutcomes.records(outcome, out, terms);
        }
        if (revisions != null) {
            RevisionOutcomes.records(
                    outcome, out, revisions, postIndex, userIndex, context.installer(), context.window());
        }
        return outcome;
    }

    /** What normalizing a post record needs: the indexes, the installer, the home URL and the run's window. */
    record PostContext(
            Map<Long, Integer> userIndex,
            Map<Long, Integer> postIndex,
            long installer,
            String home,
            RunWindow window,
            boolean guids) {}

    private static void postResult(
            Outcome outcome,
            int op,
            ScenarioOp.PostOp post,
            JsonNode result,
            String at,
            PostContext context,
            TermOutcomes.TermContext terms,
            RevisionOutcomes.RevisionContext revisions) {
        if (post instanceof ScenarioOp.TermOp term) {
            TermOutcomes.result(outcome, op, term, result, terms);
            return;
        }
        if (post instanceof ScenarioOp.RevisionOp revision) {
            RevisionOutcomes.result(outcome, op, revision, result, revisions, context.postIndex());
            return;
        }
        if (result.path("missing").asBoolean()) {
            outcome.done(op, post.kind(), "missing");
            return;
        }
        if (result.hasNonNull("error")) {
            outcome.done(op, post.kind(), "refused:" + result.get("error").asText());
            return;
        }
        switch (post) {
            case ScenarioOp.SiteOption option -> outcome.done(op, option.kind(), changed(result, at));
            case ScenarioOp.FrontPage front -> outcome.done(op, front.kind(), changed(result, at));
            case ScenarioOp.InsertPost insert ->
                outcome.postCreated(
                        op, context.postIndex().get(field(result, "id", at).asLong()));
            case ScenarioOp.UpdatePost update -> outcome.done(op, update.kind(), okay(result, at));
            case ScenarioOp.PublishPost publish -> outcome.done(op, publish.kind(), okay(result, at));
            case ScenarioOp.TrashPost trash ->
                outcome.done(op, trash.kind(), field(result, "result", at).asText());
            case ScenarioOp.UntrashPost untrash ->
                outcome.done(op, untrash.kind(), field(result, "result", at).asText());
            case ScenarioOp.DeletePost delete ->
                outcome.done(op, delete.kind(), field(result, "result", at).asText());
            case ScenarioOp.Stick stick -> outcome.done(op, stick.kind(), okay(result, at));
            case ScenarioOp.QueryPosts query -> {
                List<String> refs = new ArrayList<>();
                field(result, "ids", at).forEach(id -> refs.add(PostRecords.postRef(id.asLong(), context.postIndex())));
                outcome.queried(
                        op,
                        query.args().unordered() ? PostRecords.sortedRefs(refs) : refs,
                        field(result, "found", at).asLong());
            }
            case ScenarioOp.PageUri uri ->
                outcome.done(
                        op,
                        uri.kind(),
                        PostRecords.pageUri(field(result, "uri", at).asText(), context.postIndex()));
            case ScenarioOp.ReadPost read ->
                outcome.observed("op" + op + ":read_post", record(field(result, "post", at), context, at));
            case ScenarioOp.UpdatePostMeta meta -> outcome.done(op, meta.kind(), changed(result, at));
            case ScenarioOp.TermOp term -> throw new IllegalStateException("term results are normalized above");
            case ScenarioOp.RevisionOp revision ->
                throw new IllegalStateException("revision results are normalized above");
        }
    }

    /** A driver post record, normalized: references by index, run-time dates as now, guid on the home URL. */
    static ObservedPost record(JsonNode post, PostContext context, String at) {
        if (post.path("deleted").asBoolean()) {
            return ObservedPost.DELETED;
        }
        RunWindow window = context.window();
        long ownId = field(post, "id", at).asLong();
        String dateGmt = text(post, "date_gmt", at);
        String modifiedGmt = text(post, "modified_gmt", at);
        PostRecords.Record record = PostRecords.record()
                .field("type", text(post, "type", at))
                .field("status", text(post, "status", at))
                .field("slug", PostRecords.slug(text(post, "slug", at), ownId, context.postIndex()))
                .field("title", text(post, "title", at))
                .field("content", text(post, "content", at))
                .field("excerpt", text(post, "excerpt", at))
                .field(
                        "author",
                        PostRecords.userRef(
                                field(post, "author", at).asLong(), context.userIndex(), context.installer()))
                .field("parent", PostRecords.postRef(field(post, "parent", at).asLong(), context.postIndex()))
                .field("menu_order", text(post, "menu_order", at))
                .field("date", window.showLocal(text(post, "date", at)))
                .field("date_gmt", PostRecords.gmtText(dateGmt, window))
                .field("date_offset", PostRecords.offsetMinutes(text(post, "date", at), dateGmt))
                .field("modified", window.showLocal(text(post, "modified", at)))
                .field("modified_gmt", PostRecords.gmtText(modifiedGmt, window))
                .field("modified_offset", PostRecords.offsetMinutes(text(post, "modified", at), modifiedGmt))
                .field("comment_status", text(post, "comment_status", at))
                .field("ping_status", text(post, "ping_status", at))
                .field("password", text(post, "password", at))
                .field("sticky", field(post, "sticky", at).asBoolean() ? "1" : "0");
        if (context.guids()) {
            record.field("guid", PostRecords.guid(text(post, "guid", at), context.home(), context.postIndex()));
        }
        field(post, "meta", at).properties().forEach(entry -> {
            List<String> values = new ArrayList<>();
            entry.getValue().forEach(value -> values.add(value.asText()));
            record.meta(
                    entry.getKey(), PostRecords.metaText(entry.getKey(), values, ownId, context.postIndex(), window));
        });
        return record.build();
    }

    private static String text(JsonNode node, String name, String where) {
        return field(node, name, where).asText();
    }

    private static String changed(JsonNode result, String at) {
        return result.path("missing").asBoolean()
                ? "missing"
                : "changed=" + field(result, "changed", at).asBoolean();
    }

    private static String okay(JsonNode result, String at) {
        if (result.path("missing").asBoolean()) {
            return "missing";
        }
        if (field(result, "ok", at).asBoolean()) {
            return "ok";
        }
        return result.hasNonNull("error") ? "failed:" + result.get("error").asText() : "failed";
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
