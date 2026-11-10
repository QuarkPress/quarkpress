package org.quarkpress.testing.wprestparity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.quarkpress.testing.wporacle.OracleJson;
import org.quarkpress.testing.wprestparity.RestScenario.SetupStep;

/**
 * One side of the comparison: WordPress ({@code php -S}) or QuarkPress (the test application). Each scenario gets a
 * fresh subdirectory site with the five users, {@code /%postname%/} permalinks and the custom type {@code book}; the
 * setup runs through the side's own API; then the requests are sent.
 */
public interface Side extends AutoCloseable {

    /** A scenario's fresh site: its path and the refs it starts with ("t:uncategorized" and "u:<user>" for the five). */
    record ScenarioSite(String path, Map<String, Long> refs) {
        public ScenarioSite {
            Objects.requireNonNull(path, "path");
            refs = Map.copyOf(refs);
            for (String ref : RestScenario.STARTING_REFS) {
                if (!refs.containsKey(ref)) {
                    throw new IllegalStateException("the new site " + path + " has no starting ref " + ref);
                }
            }
        }
    }

    /**
     * The modification date the setup leaves on its first post; each later post of the setup is one second younger.
     * It lies after every fixture date the scenarios give and before any request, so a request that writes a post's
     * dates shows as {@code {now}} while one that leaves them shows the fixed date, on both sides alike.
     */
    Instant BACKDATED = Instant.parse("2026-10-01T00:00:00Z");

    /** A date to the second, as the backdate operations take it. */
    DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /**
     * One setup post's dates after the setup: its modification date {@code at} ({@code yyyy-MM-ddTHH:mm:ss}, UTC), and
     * its post date too when {@code date} is true (a post the setup gave no date).
     */
    record Backdate(long id, String at, boolean date) {}

    /**
     * The dates the setup leaves on the posts it created ({@code p:} refs of post and draft steps), in setup order: the
     * n-th post's at {@link #BACKDATED} plus n seconds; a post step without {@code date_gmt} and every auto-draft get
     * their post date moved too. Each side applies them after the last step, so its own setup writes count for nothing.
     */
    static List<Backdate> backdates(List<SetupStep> steps, Map<String, Long> created) {
        List<Backdate> backdates = new ArrayList<>();
        for (SetupStep step : steps) {
            String ref;
            boolean undated;
            switch (step) {
                case SetupStep.Post post -> {
                    ref = post.ref();
                    undated = !post.fields().has("date_gmt");
                }
                case SetupStep.Draft draft -> {
                    ref = draft.ref();
                    undated = true;
                }
                default -> {
                    continue;
                }
            }
            Long id = created.get(ref);
            if (id != null) {
                LocalDateTime at = LocalDateTime.ofInstant(BACKDATED.plusSeconds(backdates.size()), ZoneOffset.UTC);
                backdates.add(new Backdate(id, SECONDS.format(at), undated));
            }
        }
        return backdates;
    }

    /** {@link #backdates} as the JSON body both sides' backdate operations take: {@code {"posts": [...]}}. */
    static ObjectNode backdateBody(List<Backdate> backdates) {
        ObjectNode body = OracleJson.MAPPER.createObjectNode();
        ArrayNode posts = body.putArray("posts");
        for (Backdate backdate : backdates) {
            posts.addObject().put("id", backdate.id()).put("at", backdate.at()).put("date", backdate.date());
        }
        return body;
    }

    /**
     * The request header that marks a call to be sent over HTTPS ({@link RestScenario#call}): WordPress's side sends it
     * as it is (its router then sets PHP's {@code HTTPS} server variable, a harness rule, since {@code php -S} has no
     * TLS); QuarkPress's side sends {@code X-Forwarded-Proto: https} in its place. Neither header is compared.
     */
    String HTTPS_MARKER = "X-QP-Parity-Https";

    /** Creates {@code /<id>/} with the five users. */
    ScenarioSite newSite(String scenarioId) throws Exception;

    /**
     * Runs the scenario's setup steps on the site; answers ref to id ("t:news", "p:1", "m:photo", ...) for what they
     * created. A media step uploads without sizes unless the scenario is {@link RestScenario#editor}.
     */
    Map<String, Long> setUp(String sitePath, RestScenario scenario) throws Exception;

    /** Sends a call whose target is relative to the site. */
    Exchange send(String sitePath, RestCall call) throws Exception;

    @Override
    void close();

    /** The REST base of a setup term's taxonomy. */
    static String restBase(String taxonomy) {
        return switch (taxonomy) {
            case "category" -> "categories";
            case "post_tag" -> "tags";
            default -> throw new IllegalArgumentException("no REST base for taxonomy " + taxonomy);
        };
    }

    /** {@code call} with its target moved under {@code sitePath}; everything else, header lines included, kept. */
    static RestCall underSite(String sitePath, RestCall call) {
        String prefix = sitePath.endsWith("/") ? sitePath.substring(0, sitePath.length() - 1) : sitePath;
        return new RestCall(
                call.as(),
                call.method(),
                prefix + call.target(),
                call.headers(),
                call.contentType(),
                call.body(),
                call.headerLines());
    }

    /** The path of the site a user gets by an own-site step of the scenario at {@code sitePath}. */
    static String ownSitePath(String sitePath) {
        String site = sitePath.endsWith("/") ? sitePath.substring(0, sitePath.length() - 1) : sitePath;
        return site + "-own/";
    }

    /** The id of a ref among {@code refs}; an unknown ref fails. */
    static long refId(Map<String, Long> refs, String ref) {
        Long id = refs.get(ref);
        if (id == null) {
            throw new IllegalArgumentException("unknown ref " + ref);
        }
        return id;
    }

    /**
     * A post step's fields with every ref replaced by its id on this side ({@code parent}, {@code featured_media},
     * the term lists); {@code thumbnail_meta} becomes the {@code _thumbnail_id} meta value, the id as text, as
     * WordPress stores it.
     */
    static ObjectNode resolvedFields(SetupStep.Post post, Map<String, Long> refs) {
        ObjectNode fields = post.fields().deepCopy();
        if (fields.has("thumbnail_meta")) {
            long thumbnail = refId(refs, fields.remove("thumbnail_meta").textValue());
            fields.withObject("meta").put("_thumbnail_id", Long.toString(thumbnail));
        }
        for (String key : List.of("parent", "featured_media")) {
            if (fields.has(key)) {
                fields.put(key, refId(refs, fields.get(key).textValue()));
            }
        }
        for (String key : List.of("categories", "tags")) {
            if (fields.has(key)) {
                ArrayNode ids = OracleJson.MAPPER.createArrayNode();
                for (JsonNode term : fields.get(key)) {
                    ids.add(refId(refs, term.textValue()));
                }
                fields.set(key, ids);
            }
        }
        return fields;
    }

    /**
     * The text fields of a media step's upload, as both sides send them: its fields in order, the {@code post} ref
     * replaced by its id, then {@code generate_sub_sizes=false} unless the scenario has an image editor (WordPress
     * without one refuses a declared image otherwise).
     */
    static Map<String, String> mediaFields(SetupStep.Media media, Map<String, Long> refs, boolean editor) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = media.fields().fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            String value = field.getValue().textValue();
            fields.put(field.getKey(), field.getKey().equals("post") ? String.valueOf(refId(refs, value)) : value);
        }
        if (!editor) {
            fields.put("generate_sub_sizes", "false");
        }
        return fields;
    }

    /** The JSON body of a term step's REST request, with the parent's id when it has one. */
    static String termBody(SetupStep.Term term, Map<String, Long> refs) {
        ObjectNode body = OracleJson.MAPPER.createObjectNode().put("name", term.name());
        term.parentRef().ifPresent(parent -> body.put("parent", Objects.requireNonNull(refs.get(parent), parent)));
        return body.toString();
    }

    /** The WordPress side: {@link WordPressRestSite}'s REST API and its rest-setup.php operations. */
    static Side wordpress(WordPressRestSite site) {
        return new WordPress(site);
    }

    /** The WordPress side of the comparison. */
    final class WordPress implements Side {
        private final WordPressRestSite site;
        private final RestClient client;
        private final Map<String, Map<String, Long>> refsBySite = new HashMap<>();

        WordPress(WordPressRestSite site) {
            this.site = Objects.requireNonNull(site, "site");
            this.client = new RestClient(site.root(), RestClient.applicationPasswords(site));
        }

        /** The WordPress site this side runs on, for the operations a run asks of it ({@code extensions}). */
        public WordPressRestSite site() {
            return site;
        }

        @Override
        public ScenarioSite newSite(String scenarioId) throws IOException, InterruptedException {
            ObjectNode args = OracleJson.MAPPER.createObjectNode();
            args.put("path", "/" + scenarioId + "/");
            args.put("title", "Scenario " + scenarioId);
            JsonNode answer = site.operation("create_site", args);
            Map<String, Long> refs = new LinkedHashMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = answer.path("refs").fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> ref = it.next();
                refs.put(ref.getKey(), ref.getValue().longValue());
            }
            ScenarioSite created = new ScenarioSite(answer.path("path").asText(), refs);
            refsBySite.put(created.path(), new HashMap<>(refs));
            return created;
        }

        @Override
        public Map<String, Long> setUp(String sitePath, RestScenario scenario) throws Exception {
            List<SetupStep> steps = scenario.setup();
            Map<String, Long> known = refsBySite.computeIfAbsent(sitePath, p -> new HashMap<>());
            Map<String, Long> created = new LinkedHashMap<>();
            if (scenario.uploads()) {
                // the shared tree keeps the uploads of earlier runs, whose new sites reuse the same ids: a file left
                // there would rename this run's upload
                site.operation(
                        "clear_uploads", OracleJson.MAPPER.createObjectNode().put("site", sitePath));
            }
            for (SetupStep step : steps) {
                switch (step) {
                    case SetupStep.Post post -> {
                        ObjectNode args = OracleJson.MAPPER.createObjectNode();
                        args.put("site", sitePath)
                                .put("as", post.as())
                                .put("type", post.type())
                                .put("status", post.status())
                                .put("title", post.title());
                        args.set("fields", postFields(resolvedFields(post, known)));
                        created.put(
                                post.ref(),
                                site.operation("post", args).path("id").longValue());
                    }
                    case SetupStep.Revision revision -> {
                        ObjectNode args = OracleJson.MAPPER.createObjectNode();
                        args.put("site", sitePath)
                                .put("as", revision.as())
                                .put("id", refId(known, revision.postRef()))
                                .put("title", revision.title())
                                .put("content", revision.content());
                        created.put(
                                revision.ref(),
                                site.operation("revision", args).path("id").longValue());
                    }
                    case SetupStep.Autosave autosave -> {
                        ObjectNode args = OracleJson.MAPPER.createObjectNode();
                        args.put("site", sitePath)
                                .put("as", autosave.as())
                                .put("post", refId(known, autosave.postRef()))
                                .put("title", autosave.title())
                                .put("content", autosave.content());
                        JsonNode answer = site.operation("autosave", args);
                        if (answer.path("id").longValue() == 0) {
                            throw new IllegalStateException("WordPress setup autosave " + autosave + ": " + answer);
                        }
                        created.put(autosave.ref(), answer.get("id").longValue());
                    }
                    case SetupStep.Draft draft -> {
                        ObjectNode args = OracleJson.MAPPER.createObjectNode();
                        args.put("site", sitePath).put("as", draft.as()).put("type", draft.type());
                        JsonNode answer = site.operation("auto_draft", args);
                        if (answer.path("id").longValue() == 0) {
                            throw new IllegalStateException("WordPress setup draft " + draft + ": " + answer);
                        }
                        created.put(draft.ref(), answer.get("id").longValue());
                    }
                    case SetupStep.Lock lock -> {
                        ObjectNode args = OracleJson.MAPPER.createObjectNode();
                        args.put("site", sitePath)
                                .put("post", refId(known, lock.postRef()))
                                .put("user", lock.user())
                                .put("age", lock.ageSeconds());
                        site.operation("lock", args);
                    }
                    case SetupStep.OwnSite ownSite -> {
                        ObjectNode args = OracleJson.MAPPER.createObjectNode();
                        args.put("site", sitePath)
                                .put("user", ownSite.user())
                                .put("path", ownSitePath(sitePath))
                                .put("title", "Own site of " + ownSite.user());
                        site.operation("own_site", args);
                    }
                    case SetupStep.Term term -> {
                        // the rest_route form reaches the REST API whatever permalinks an earlier option step set
                        Exchange answer = send(
                                sitePath,
                                RestCall.json(
                                        term.as(),
                                        "POST",
                                        "/?rest_route=/wp/v2/" + restBase(term.taxonomy()),
                                        termBody(term, known)));
                        if (answer.status() != 201) {
                            throw new IllegalStateException(
                                    "WordPress setup term " + term + ": " + answer.status() + " " + answer.text());
                        }
                        created.put(term.ref(), answer.json().get("id").longValue());
                    }
                    case SetupStep.Option option -> {
                        ObjectNode args = OracleJson.MAPPER.createObjectNode();
                        args.put("site", sitePath).put("name", option.name());
                        args.set("value", option.value());
                        site.operation("option", args);
                    }
                    case SetupStep.Leave leave -> {
                        ObjectNode args = OracleJson.MAPPER.createObjectNode();
                        args.put("site", sitePath).put("user", leave.user());
                        site.operation("leave", args);
                    }
                    case SetupStep.Media media -> {
                        Exchange answer = send(
                                sitePath,
                                RestCall.multipart(
                                        media.as(),
                                        "/?rest_route=/wp/v2/media",
                                        media.name(),
                                        media.type(),
                                        MediaScenarios.fixture(media.fixture()),
                                        mediaFields(media, known, scenario.editor())));
                        if (answer.status() != 201) {
                            throw new IllegalStateException(
                                    "WordPress setup media " + media + ": " + answer.status() + " " + answer.text());
                        }
                        created.put(media.ref(), answer.json().get("id").longValue());
                    }
                }
                known.putAll(created);
            }
            List<Backdate> backdates = backdates(steps, created);
            if (!backdates.isEmpty()) {
                ObjectNode args = backdateBody(backdates);
                args.put("site", sitePath);
                site.operation("backdate", args);
            }
            return created;
        }

        /**
         * A post step's resolved fields as the {@code post} operation takes them: the column fields under their
         * {@code post_} names ({@code date_gmt} as both {@code post_date} and {@code post_date_gmt}, the site's clock
         * being UTC), the term lists under {@code terms}, {@code featured_media} as {@code thumbnail}.
         */
        static ObjectNode postFields(ObjectNode fields) {
            ObjectNode out = OracleJson.MAPPER.createObjectNode();
            Map<String, String> columns = Map.of(
                    "content", "post_content",
                    "excerpt", "post_excerpt",
                    "password", "post_password",
                    "parent", "post_parent",
                    "slug", "post_name",
                    "menu_order", "menu_order",
                    "comment_status", "comment_status");
            for (Iterator<Map.Entry<String, JsonNode>> it = fields.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                String key = field.getKey();
                JsonNode value = field.getValue();
                switch (key) {
                    case "date_gmt" -> {
                        String mysql = value.textValue().replace('T', ' ');
                        out.put("post_date", mysql).put("post_date_gmt", mysql);
                    }
                    case "categories", "tags" ->
                        out.withObject("terms").set(key.equals("categories") ? "category" : "post_tag", value);
                    case "featured_media" -> out.set("thumbnail", value);
                    case "sticky", "meta", "format", "template" -> out.set(key, value);
                    default -> out.set(columns.get(key), value);
                }
            }
            return out;
        }

        @Override
        public Exchange send(String sitePath, RestCall call) throws IOException, InterruptedException {
            return client.send(underSite(sitePath, call));
        }

        @Override
        public void close() {
            site.close();
        }
    }
}
