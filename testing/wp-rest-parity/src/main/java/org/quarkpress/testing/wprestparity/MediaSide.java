package org.quarkpress.testing.wprestparity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.quarkpress.testing.wporacle.OracleJson;
import org.quarkpress.testing.wprestparity.MediaScenario.Setup;
import org.quarkpress.testing.wprestparity.MediaScenario.Step;

/**
 * One side of the media parity comparison: WordPress (its REST media routes, served by {@code php -S}, and the
 * rest-setup.php operations) or QuarkPress (the media test application). Each scenario gets a fresh subdirectory site
 * where the five users have their roles; site paths look like {@code /m01/}. Ids are each side's own: the runner
 * ({@link MediaScenarios#run}) names them by creation order.
 */
public interface MediaSide extends AutoCloseable {

    /**
     * What an operation answered: accepted (with the attachment's id for an upload), or refused with the side's error
     * {@code code} and {@code message}.
     */
    record Outcome(boolean accepted, long id, String code, String message) {
        public Outcome {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }

        public static Outcome accepted(long id) {
            return new Outcome(true, id, "", "");
        }

        public static Outcome refused(String code, String message) {
            return new Outcome(false, 0, code, message);
        }
    }

    /** Creates the scenario's site {@code /<scenarioId>/}; answers its path. */
    String newSite(String scenarioId) throws Exception;

    /** Runs the setup on the site; answers the refs: the five users ({@code u:<user>}) and the setup's posts. */
    Map<String, Long> setup(String site, List<Setup> setup) throws Exception;

    /** Uploads a file; the {@code post} field, if any, is an id already. */
    Outcome upload(String site, Step.Upload upload) throws Exception;

    /**
     * The attachment as stored, in the shape of the media test application's {@code /it/media/{id}/wordpress}: {@code
     * post} (the row's columns under WordPress's names), {@code meta} ({@code _wp_attached_file}, {@code
     * _wp_attachment_metadata} as JSON, {@code _wp_attachment_image_alt} and any other meta the side stores) and
     * {@code files} (the sorted names in the attachment's folder).
     */
    ObjectNode attachment(String site, long id) throws Exception;

    /** Updates an attachment as {@code as} with REST fields ({@code post} an id already). */
    Outcome update(String site, String as, long id, Map<String, String> fields) throws Exception;

    /** Makes {@code attachment} the featured image of {@code post}. */
    Outcome feature(String site, long post, long attachment) throws Exception;

    /** The post's {@code _thumbnail_id} values. */
    List<Long> featured(String site, long post) throws Exception;

    /** Deletes the attachment for good, as {@code as}. */
    Outcome delete(String site, String as, long id) throws Exception;

    /** WordPress's post-process (make the missing sizes), as {@code as}. */
    Outcome process(String site, String as, long id) throws Exception;

    /** The ids of the site's attachments matching the query. */
    List<Long> query(String site, Step.Query query) throws Exception;

    /** The sorted file names in a folder of the site's uploads ({@code 2026/11}, relative to the site's base). */
    List<String> files(String site, String folder) throws Exception;

    /** Ends the scenario: puts back the network options its setup changed. */
    void finish(String site) throws Exception;

    @Override
    void close();

    /** The WordPress side over {@code client} (application passwords) and the site's rest-setup.php operations. */
    static MediaSide wordpress(WordPressRestSite site, RestClient client) {
        return new WordPress(site, client);
    }

    /** The current upload folder ({@code yyyy/MM}, UTC: the sites' time zone) of a moment. */
    static String monthFolder(java.time.Instant at) {
        java.time.LocalDate date = at.atZone(java.time.ZoneOffset.UTC).toLocalDate();
        return String.format(java.util.Locale.ROOT, "%04d/%02d", date.getYear(), date.getMonthValue());
    }

    /**
     * The JSON body of a refusal: text before the first {@code {} (output printed ahead of the body) is skipped; a
     * body that is no JSON object answers code {@code http-<status>} with the text as the message.
     */
    static Outcome refusal(Exchange exchange) {
        String text = exchange.text();
        int start = text.indexOf('{');
        if (start >= 0) {
            try {
                JsonNode body = OracleJson.MAPPER.readTree(text.substring(start));
                if (body != null && body.isObject() && body.has("code")) {
                    return Outcome.refused(
                            body.path("code").asText(), body.path("message").asText(""));
                }
            } catch (IOException notJson) {
                // answered below
            }
        }
        return Outcome.refused("http-" + exchange.status(), text.strip());
    }

    /** The WordPress side of the comparison. */
    final class WordPress implements MediaSide {

        private final WordPressRestSite site;
        private final RestClient client;
        /** Per site path: the network options its setup changed, with their earlier values (null when absent). */
        private final Map<String, Map<String, JsonNode>> changed = new HashMap<>();

        WordPress(WordPressRestSite site, RestClient client) {
            this.site = Objects.requireNonNull(site, "site");
            this.client = Objects.requireNonNull(client, "client");
        }

        @Override
        public String newSite(String scenarioId) throws IOException, InterruptedException {
            String path = "/" + scenarioId + "/";
            site.operation("create_site", args().put("path", path).put("title", "Scenario " + scenarioId));
            // Harness rule: a site id an earlier run of the shared tree used starts without files.
            site.operation("clear_uploads", args().put("site", path));
            return path;
        }

        @Override
        public Map<String, Long> setup(String sitePath, List<Setup> setup) throws Exception {
            Map<String, Long> refs = new LinkedHashMap<>();
            for (String user : WordPressRestSite.USERS) {
                refs.put("u:" + user, this.site.userId(user));
            }
            for (Setup entry : setup) {
                switch (entry) {
                    case Setup.Post post -> {
                        ObjectNode input = args().put("site", sitePath)
                                .put("as", "alice")
                                .put("type", post.type())
                                .put("status", post.status())
                                .put("title", "Post " + post.ref());
                        ObjectNode fields = input.putObject("fields");
                        post.date().ifPresent(date -> fields.put("post_date", date));
                        refs.put(
                                post.ref(),
                                site.operation("post", input).get("id").longValue());
                    }
                    case Setup.Option option -> {
                        ObjectNode input = args().put("site", sitePath).put("name", option.name());
                        input.set("value", option.value());
                        site.operation("option", input);
                    }
                    case Setup.Folder folder -> {
                        ObjectNode input = args().put("site", sitePath)
                                .put("current", true)
                                .put("clear", false);
                        ObjectNode files = input.putObject("files");
                        folder.files()
                                .forEach((name, fixture) -> files.put(
                                        name, Base64.getEncoder().encodeToString(MediaScenarios.fixture(fixture))));
                        site.operation("prepare_folder", input);
                    }
                    case Setup.NetworkOption option -> {
                        Map<String, JsonNode> earlier = changed.computeIfAbsent(sitePath, p -> new LinkedHashMap<>());
                        if (!earlier.containsKey(option.name())) {
                            JsonNode value = site.operation("network_option", args().put("name", option.name()))
                                    .get("value");
                            earlier.put(option.name(), value == null ? NullNode.getInstance() : value);
                        }
                        ObjectNode input = args().put("name", option.name());
                        input.set("value", option.value());
                        site.operation("set_network_option", input);
                    }
                }
            }
            return refs;
        }

        @Override
        public Outcome upload(String sitePath, Step.Upload upload) throws Exception {
            Exchange answer = client.send(RestCall.multipart(
                    upload.as(),
                    sitePath + "wp-json/wp/v2/media",
                    upload.clientName(),
                    upload.declaredType(),
                    MediaScenarios.fixture(upload.fixture()),
                    upload.fields()));
            if (answer.status() == 201) {
                return Outcome.accepted(answer.json().get("id").longValue());
            }
            return refusal(answer);
        }

        @Override
        public ObjectNode attachment(String sitePath, long id) throws Exception {
            JsonNode stored =
                    site.operation("attachment", args().put("site", sitePath).put("id", id));
            if (!stored.path("exists").asBoolean()) {
                throw new IllegalStateException("WordPress has no attachment " + id + " on " + sitePath);
            }
            ObjectNode out = OracleJson.MAPPER.createObjectNode();
            ObjectNode post = out.putObject("post");
            for (Iterator<Map.Entry<String, JsonNode>> it = stored.get("row").fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> column = it.next();
                post.set(column.getKey(), column.getValue());
            }
            ObjectNode meta = out.putObject("meta");
            for (Iterator<Map.Entry<String, JsonNode>> it = stored.get("meta").fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> key = it.next();
                if (key.getKey().equals("_wp_attachment_metadata")) {
                    continue;
                }
                JsonNode values = key.getValue();
                meta.set(key.getKey(), values.isArray() && values.size() == 1 ? values.get(0) : values);
            }
            if (stored.get("meta").has("_wp_attachment_metadata")) {
                meta.set("_wp_attachment_metadata", stored.get("metadata"));
            }
            ArrayNode files = out.putArray("files");
            for (JsonNode entry : stored.path("folder_listing")) {
                String path = entry.path("path").asText();
                if (!path.endsWith("/")) {
                    files.add(path.substring(path.lastIndexOf('/') + 1));
                }
            }
            return out;
        }

        @Override
        public Outcome update(String sitePath, String as, long id, Map<String, String> fields) throws Exception {
            ObjectNode body = OracleJson.MAPPER.createObjectNode();
            fields.forEach((name, value) -> {
                if (name.equals("post")) {
                    body.put(name, Long.parseLong(value));
                } else {
                    body.put(name, value);
                }
            });
            Exchange answer =
                    client.send(RestCall.json(as, "POST", sitePath + "wp-json/wp/v2/media/" + id, body.toString()));
            return answer.status() == 200 ? Outcome.accepted(id) : refusal(answer);
        }

        @Override
        public Outcome feature(String sitePath, long post, long attachment) throws Exception {
            // Harness rule: the featured image is set as WordPress's set_post_thumbnail stores it, the id as text.
            ObjectNode input = args().put("site", sitePath).put("post", post).put("key", "_thumbnail_id");
            input.put("value", String.valueOf(attachment));
            site.operation("set_meta", input);
            return Outcome.accepted(post);
        }

        @Override
        public List<Long> featured(String sitePath, long post) throws Exception {
            JsonNode values = site.operation(
                            "meta",
                            args().put("site", sitePath).put("post", post).put("key", "_thumbnail_id"))
                    .path("values");
            List<Long> ids = new ArrayList<>();
            for (JsonNode value : values) {
                ids.add(Long.parseLong(value.asText()));
            }
            return ids;
        }

        @Override
        public Outcome delete(String sitePath, String as, long id) throws Exception {
            Exchange answer = client.send(new RestCall(
                    as,
                    "DELETE",
                    sitePath + "wp-json/wp/v2/media/" + id + "?force=true",
                    Map.of(),
                    java.util.Optional.empty(),
                    java.util.Optional.empty()));
            return answer.status() == 200 ? Outcome.accepted(id) : refusal(answer);
        }

        @Override
        public Outcome process(String sitePath, String as, long id) throws Exception {
            Exchange answer = client.send(RestCall.json(
                    as,
                    "POST",
                    sitePath + "wp-json/wp/v2/media/" + id + "/post-process",
                    "{\"action\":\"create-image-subsizes\"}"));
            return answer.status() / 100 == 2 ? Outcome.accepted(id) : refusal(answer);
        }

        @Override
        public List<Long> query(String sitePath, Step.Query query) throws Exception {
            ObjectNode input = args().put("site", sitePath);
            ObjectNode queryArgs = input.putObject("args");
            if (query.mimeTypes().size() == 1) {
                queryArgs.put("post_mime_type", query.mimeTypes().get(0));
            } else {
                ArrayNode values = queryArgs.putArray("post_mime_type");
                query.mimeTypes().forEach(values::add);
            }
            List<Long> ids = new ArrayList<>();
            for (JsonNode post : site.operation("query_attachments", input).path("posts")) {
                ids.add(post.get("id").longValue());
            }
            return ids;
        }

        @Override
        public List<String> files(String sitePath, String folder) throws Exception {
            JsonNode listing = site.operation(
                            "uploads_listing", args().put("site", sitePath).put("folder", folder))
                    .path("listing");
            String prefix = folder.isEmpty() ? "" : folder + "/";
            List<String> names = new ArrayList<>();
            for (JsonNode entry : listing) {
                String path = entry.path("path").asText();
                if (path.startsWith(prefix) && !path.endsWith("/") && path.indexOf('/', prefix.length()) < 0) {
                    names.add(path.substring(prefix.length()));
                }
            }
            names.sort(null);
            return names;
        }

        @Override
        public void finish(String sitePath) throws Exception {
            Map<String, JsonNode> earlier = changed.remove(sitePath);
            if (earlier != null) {
                for (Map.Entry<String, JsonNode> option : earlier.entrySet()) {
                    ObjectNode input = args().put("name", option.getKey());
                    input.set("value", option.getValue());
                    site.operation("set_network_option", input);
                }
            }
        }

        @Override
        public void close() {
            site.close();
        }

        private static ObjectNode args() {
            return OracleJson.MAPPER.createObjectNode();
        }
    }
}
