package org.quarkpress.it.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.testing.wporacle.OracleJson;
import org.quarkpress.testing.wprestparity.Exchange;
import org.quarkpress.testing.wprestparity.MediaScenario.Setup;
import org.quarkpress.testing.wprestparity.MediaScenario.Step;
import org.quarkpress.testing.wprestparity.MediaScenarios;
import org.quarkpress.testing.wprestparity.MediaSide;
import org.quarkpress.testing.wprestparity.RestCall;
import org.quarkpress.testing.wprestparity.RestClient;
import org.quarkpress.testing.wprestparity.WordPressRestSite;

/**
 * The QuarkPress side of the media parity comparison: this test application over HTTP with the dev realm's Keycloak
 * tokens, every request naming {@code localhost} (the network's domain) as its host. alice creates each scenario's
 * site and gives the five users their WordPress roles there; uploads, updates, deletes, post-processing and queries go
 * through the media test resource ({@code /<site>/it/media}), setup posts, network options and featured images through
 * the setup resource. A setup folder's files are written straight into the filesystem storage's directory, where the
 * site's uploads live ({@code sites/<id>/<year>/<month>}), and a folder is listed from there.
 */
final class QuarkPressMediaSide implements MediaSide {

    /** The roles the five users have on every scenario site, as on WordPress's side. */
    static final Map<String, String> ROLES = Map.of(
            "alice", "administrator", "bob", "editor", "carol", "author", "dave", "contributor", "erin", "subscriber");

    private static final long TOKEN_SECONDS = 60;

    private record Token(String value, long fetchedAt) {}

    private final KeycloakTestClient keycloak;
    private final RestClient client;
    private final Path storage;
    private final Map<String, Token> tokens = new HashMap<>();
    private final Map<String, Long> userIds = new LinkedHashMap<>();
    /** The storage folder of each scenario site's uploads ({@code sites/<id>}). */
    private final Map<String, String> bases = new HashMap<>();
    /** Per site path: the network options its setup changed, with their earlier values (null when absent). */
    private final Map<String, Map<String, JsonNode>> changed = new HashMap<>();

    QuarkPressMediaSide(URI root, KeycloakTestClient keycloak, Path storage) throws Exception {
        this.keycloak = keycloak;
        this.client = new RestClient(root, this::authorization);
        this.storage = storage;
        // alice's profile (the initial super admin) is made at start; the others are provisioned at each user's first
        // call, in WordPress's order of creation
        for (String user : WordPressRestSite.USERS) {
            Exchange me = send("/", RestCall.get(user, "/it/me"));
            if (me.status() != 200 || !me.json().path("user").isIntegralNumber()) {
                throw new IllegalStateException("GET /it/me as " + user + ": " + me.status() + " " + me.text());
            }
            userIds.put(user, me.json().get("user").longValue());
        }
    }

    private Optional<String> authorization(String as) {
        if (!WordPressRestSite.USERS.contains(as)) {
            throw new IllegalArgumentException("unknown caller " + as);
        }
        long now = System.nanoTime();
        Token token = tokens.get(as);
        if (token == null || now - token.fetchedAt() > TOKEN_SECONDS * 1_000_000_000L) {
            token = new Token(keycloak.getAccessToken(as), now);
            tokens.put(as, token);
        }
        return Optional.of("Bearer " + token.value());
    }

    @Override
    public String newSite(String scenarioId) throws Exception {
        String path = "/" + scenarioId + "/";
        ObjectNode site = OracleJson.MAPPER.createObjectNode().put("path", path).put("title", "Scenario " + scenarioId);
        Exchange created = expect(send("/", RestCall.json("alice", "POST", "/it/sites", site.toString())), 200, path);
        bases.put(path, "sites/" + created.json().get("id").longValue());
        for (Map.Entry<String, Long> user : userIds.entrySet()) {
            ObjectNode membership = OracleJson.MAPPER
                    .createObjectNode()
                    .put("user", user.getValue())
                    .put("role", ROLES.get(user.getKey()));
            expect(
                    send(path, RestCall.json("alice", "POST", "/it/memberships", membership.toString())),
                    200,
                    "role of " + user.getKey() + " on " + path);
        }
        return path;
    }

    @Override
    public Map<String, Long> setup(String site, List<Setup> setup) throws Exception {
        Map<String, Long> refs = new LinkedHashMap<>();
        userIds.forEach((user, id) -> refs.put("u:" + user, id));
        for (Setup entry : setup) {
            switch (entry) {
                case Setup.Post post -> {
                    ObjectNode body = OracleJson.MAPPER
                            .createObjectNode()
                            .put("type", post.type())
                            .put("status", post.status())
                            .put("title", "Post " + post.ref());
                    post.date().ifPresent(date -> body.put("date", date));
                    Exchange answer =
                            expect(send(site, RestCall.json("alice", "POST", "/it/posts", body.toString())), 200, post);
                    refs.put(post.ref(), answer.json().get("id").longValue());
                }
                case Setup.Option option -> {
                    if (!option.name().startsWith("qp_parity_")) {
                        throw new IllegalArgumentException("the QuarkPress side sets no site option " + option.name());
                    }
                    // a switch of WordPress's harness plugin: QuarkPress has no such option
                }
                case Setup.Folder folder -> {
                    Path dir = storage.resolve(bases.get(site)).resolve(MediaSide.monthFolder(Instant.now()));
                    Files.createDirectories(dir);
                    for (Map.Entry<String, org.quarkpress.testing.wprestparity.MediaScenario.Fixture> file :
                            folder.files().entrySet()) {
                        Files.write(dir.resolve(file.getKey()), MediaScenarios.fixture(file.getValue()));
                    }
                }
                case Setup.NetworkOption option -> {
                    Map<String, JsonNode> earlier = changed.computeIfAbsent(site, p -> new LinkedHashMap<>());
                    if (!earlier.containsKey(option.name())) {
                        JsonNode current = expect(
                                        send("/", RestCall.get("alice", "/it/network-options/" + option.name())),
                                        200,
                                        option)
                                .json();
                        earlier.put(
                                option.name(),
                                current.path("exists").asBoolean() ? current.get("value") : NullNode.getInstance());
                    }
                    setNetworkOption(option.name(), option.value());
                }
            }
        }
        return refs;
    }

    private void setNetworkOption(String name, JsonNode value) throws Exception {
        expect(
                send("/", RestCall.json("alice", "PUT", "/it/network-options/" + name, value.toString())),
                200,
                "network option " + name);
    }

    @Override
    public Outcome upload(String site, Step.Upload upload) throws Exception {
        Exchange answer = send(
                site,
                RestCall.multipart(
                        upload.as(),
                        "/it/media",
                        upload.clientName(),
                        upload.declaredType(),
                        MediaScenarios.fixture(upload.fixture()),
                        upload.fields()));
        return answer.status() == 201
                ? Outcome.accepted(answer.json().get("id").longValue())
                : MediaSide.refusal(answer);
    }

    @Override
    public ObjectNode attachment(String site, long id) throws Exception {
        return (ObjectNode) expect(send(site, RestCall.get("alice", "/it/media/" + id + "/wordpress")), 200, id)
                .json();
    }

    @Override
    public Outcome update(String site, String as, long id, Map<String, String> fields) throws Exception {
        ObjectNode body = OracleJson.MAPPER.createObjectNode();
        fields.forEach((name, value) -> {
            if (name.equals("post")) {
                body.put(name, Long.parseLong(value));
            } else {
                body.put(name, value);
            }
        });
        Exchange answer = send(site, RestCall.json(as, "PUT", "/it/media/" + id, body.toString()));
        return answer.status() == 200 ? Outcome.accepted(id) : MediaSide.refusal(answer);
    }

    @Override
    public Outcome feature(String site, long post, long attachment) throws Exception {
        expect(
                send(
                        site,
                        RestCall.json(
                                "alice",
                                "PUT",
                                "/it/posts/" + post + "/meta/_thumbnail_id",
                                TextNode.valueOf(String.valueOf(attachment)).toString())),
                200,
                "featured image of " + post);
        return Outcome.accepted(post);
    }

    @Override
    public List<Long> featured(String site, long post) throws Exception {
        JsonNode values = expect(
                        send(site, RestCall.get("alice", "/it/posts/" + post + "/meta/_thumbnail_id")),
                        200,
                        "featured image of " + post)
                .json();
        List<Long> ids = new ArrayList<>();
        for (JsonNode value : values) {
            ids.add(Long.parseLong(value.asText()));
        }
        return ids;
    }

    @Override
    public Outcome delete(String site, String as, long id) throws Exception {
        Exchange answer =
                send(site, new RestCall(as, "DELETE", "/it/media/" + id, Map.of(), Optional.empty(), Optional.empty()));
        return answer.status() == 200 ? Outcome.accepted(id) : MediaSide.refusal(answer);
    }

    @Override
    public Outcome process(String site, String as, long id) throws Exception {
        Exchange answer = send(
                site,
                new RestCall(as, "POST", "/it/media/" + id + "/process", Map.of(), Optional.empty(), Optional.empty()));
        return answer.status() == 200 ? Outcome.accepted(id) : MediaSide.refusal(answer);
    }

    @Override
    public List<Long> query(String site, Step.Query query) throws Exception {
        // each value as its own mime_type parameter, empty ones included (the test resource keeps them)
        StringBuilder target = new StringBuilder("/it/media");
        char separator = '?';
        for (String value : query.mimeTypes()) {
            target.append(separator).append("mime_type=").append(URLEncoder.encode(value, StandardCharsets.UTF_8));
            separator = '&';
        }
        Exchange answer = expect(send(site, RestCall.get(query.as(), target.toString())), 200, query);
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : answer.json()) {
            ids.add(item.get("id").longValue());
        }
        return ids;
    }

    @Override
    public List<String> files(String site, String folder) throws IOException {
        Path dir = storage.resolve(bases.get(site));
        if (!folder.isEmpty()) {
            dir = dir.resolve(folder);
        }
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return names;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                if (Files.isRegularFile(entry)) {
                    names.add(entry.getFileName().toString());
                }
            }
        }
        names.sort(null);
        return names;
    }

    @Override
    public void finish(String site) throws Exception {
        Map<String, JsonNode> earlier = changed.remove(site);
        if (earlier != null) {
            for (Map.Entry<String, JsonNode> option : earlier.entrySet()) {
                setNetworkOption(option.getKey(), option.getValue());
            }
        }
    }

    private Exchange send(String site, RestCall call) throws Exception {
        String prefix = site.endsWith("/") ? site.substring(0, site.length() - 1) : site;
        Map<String, String> headers = new LinkedHashMap<>(call.headers());
        headers.put("Host", "localhost");
        return client.send(new RestCall(
                call.as(), call.method(), prefix + call.target(), headers, call.contentType(), call.body()));
    }

    private static Exchange expect(Exchange exchange, int status, Object what) {
        if (exchange.status() != status) {
            throw new IllegalStateException(
                    "QuarkPress (" + what + "): " + exchange.call().method() + " "
                            + exchange.call().target() + " answered " + exchange.status() + " " + exchange.text());
        }
        return exchange;
    }

    @Override
    public void close() {}
}
