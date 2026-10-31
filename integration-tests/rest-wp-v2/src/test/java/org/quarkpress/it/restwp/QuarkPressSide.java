package org.quarkpress.it.restwp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.testing.wporacle.OracleJson;
import org.quarkpress.testing.wprestparity.Exchange;
import org.quarkpress.testing.wprestparity.RestCall;
import org.quarkpress.testing.wprestparity.RestClient;
import org.quarkpress.testing.wprestparity.RestScenario.SetupStep;
import org.quarkpress.testing.wprestparity.Side;
import org.quarkpress.testing.wprestparity.WordPressRestSite;

/**
 * The QuarkPress side of the REST parity comparison: this test application, reached over HTTP with the dev realm's
 * Keycloak tokens, every request naming {@code localhost} as its host (the network's domain). A scenario's site is
 * created by alice through the setup resource, with the five users given their WordPress roles there; posts, options
 * and a user's leaving the site go through the setup resource on that site, terms through the REST API.
 */
final class QuarkPressSide implements Side {

    /** The roles the five users have on every scenario site, as on WordPress's side. */
    static final Map<String, String> ROLES = Map.of(
            "alice", "administrator", "bob", "editor", "carol", "author", "dave", "contributor", "erin", "subscriber");

    /** What {@code invalid} sends: a Bearer token no issuer signed. */
    static final String INVALID_TOKEN = "Bearer not-a-valid-token";

    private static final long TOKEN_SECONDS = 60;

    private record Token(String value, long fetchedAt) {}

    private final KeycloakTestClient keycloak;
    private final RestClient client;
    private final Map<String, Token> tokens = new HashMap<>();
    private final Map<String, Long> userIds = new LinkedHashMap<>();
    private final Map<String, Map<String, Long>> refsBySite = new HashMap<>();

    QuarkPressSide(URI root, KeycloakTestClient keycloak) throws Exception {
        this.keycloak = keycloak;
        this.client = new RestClient(root, this::authorization);
        // alice's profile (the initial super admin) is made at start; the others are provisioned at each user's first
        // call, in WordPress's order of creation
        for (String user : List.of("alice", "bob", "carol", "dave", "erin")) {
            Exchange me = send("/", RestCall.get(user, "/it/me"));
            if (me.status() != 200 || !me.json().path("user").isIntegralNumber()) {
                throw new IllegalStateException("GET /it/me as " + user + ": " + me.status() + " " + me.text());
            }
            userIds.put(user, me.json().get("user").longValue());
        }
    }

    private Optional<String> authorization(String as) {
        return switch (as) {
            case "anonymous" -> Optional.empty();
            case "invalid" -> Optional.of(INVALID_TOKEN);
            default -> {
                if (!WordPressRestSite.USERS.contains(as)) {
                    throw new IllegalArgumentException("unknown caller " + as);
                }
                long now = System.nanoTime();
                Token token = tokens.get(as);
                if (token == null || now - token.fetchedAt() > TOKEN_SECONDS * 1_000_000_000L) {
                    token = new Token(keycloak.getAccessToken(as), now);
                    tokens.put(as, token);
                }
                yield Optional.of("Bearer " + token.value());
            }
        };
    }

    @Override
    public ScenarioSite newSite(String scenarioId) throws Exception {
        String path = "/" + scenarioId + "/";
        ObjectNode site = OracleJson.MAPPER.createObjectNode().put("path", path).put("title", "Scenario " + scenarioId);
        expect(send("/", RestCall.json("alice", "POST", "/it/sites", site.toString())), 200, "create site " + path);
        Map<String, Long> refs = new LinkedHashMap<>();
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
        Exchange uncategorized =
                expect(send(path, RestCall.get("alice", "/wp-json/wp/v2/categories?slug=uncategorized")), 200, path);
        JsonNode categories = uncategorized.json();
        if (categories.size() != 1 || !categories.get(0).path("id").isIntegralNumber()) {
            throw new IllegalStateException(path + " has no single Uncategorized category: " + uncategorized.text());
        }
        refs.put("t:uncategorized", categories.get(0).get("id").longValue());
        userIds.forEach((user, id) -> refs.put("u:" + user, id));
        refsBySite.put(path, new HashMap<>(refs));
        return new ScenarioSite(path, refs);
    }

    @Override
    public Map<String, Long> setUp(String sitePath, List<SetupStep> steps) throws Exception {
        Map<String, Long> known = refsBySite.computeIfAbsent(sitePath, p -> new HashMap<>());
        Map<String, Long> created = new LinkedHashMap<>();
        for (SetupStep step : steps) {
            switch (step) {
                case SetupStep.Post post -> {
                    ObjectNode body = OracleJson.MAPPER
                            .createObjectNode()
                            .put("type", post.type())
                            .put("status", post.status())
                            .put("title", post.title());
                    Exchange answer = expect(
                            send(sitePath, RestCall.json(post.as(), "POST", "/it/posts", body.toString())),
                            200,
                            "post " + post);
                    created.put(post.ref(), answer.json().get("id").longValue());
                }
                case SetupStep.Term term -> {
                    Exchange answer = expect(
                            send(
                                    sitePath,
                                    RestCall.json(
                                            term.as(),
                                            "POST",
                                            "/wp-json/wp/v2/" + Side.restBase(term.taxonomy()),
                                            Side.termBody(term, known))),
                            201,
                            "term " + term);
                    created.put(term.ref(), answer.json().get("id").longValue());
                }
                case SetupStep.Leave leave ->
                    expect(
                            send(
                                    sitePath,
                                    new RestCall(
                                            "alice",
                                            "DELETE",
                                            "/it/memberships/" + userIds.get(leave.user()),
                                            Map.of(),
                                            Optional.empty(),
                                            Optional.empty())),
                            200,
                            leave.user() + " leaves " + sitePath);
                case SetupStep.Option option ->
                    expect(
                            send(
                                    sitePath,
                                    RestCall.json(
                                            "alice",
                                            "PUT",
                                            "/it/options/" + option.name(),
                                            option.value().toString())),
                            200,
                            "option " + option.name());
            }
            known.putAll(created);
        }
        return created;
    }

    @Override
    public Exchange send(String sitePath, RestCall call) throws Exception {
        RestCall moved = Side.underSite(sitePath, call);
        Map<String, String> headers = new LinkedHashMap<>(moved.headers());
        headers.put("Host", "localhost");
        return client.send(
                new RestCall(moved.as(), moved.method(), moved.target(), headers, moved.contentType(), moved.body()));
    }

    private static Exchange expect(Exchange exchange, int status, String what) {
        if (exchange.status() != status) {
            throw new IllegalStateException(
                    "QuarkPress setup (" + what + "): " + exchange.call().method() + " "
                            + exchange.call().target() + " answered " + exchange.status() + " " + exchange.text());
        }
        return exchange;
    }

    @Override
    public void close() {}
}
