package org.quarkpress.testing.wprestparity;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RestClientTest {
    private HttpServer server;
    private RestClient client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        // Answers every Authorization line it received, one per line of the body; /echo/<name> answers that header.
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String name = path.startsWith("/echo/") ? path.substring("/echo/".length()) : "Authorization";
            List<String> lines = exchange.getRequestHeaders().getOrDefault(name, List.of());
            byte[] body = String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        client = new RestClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                as -> "anonymous".equals(as) ? Optional.empty() : Optional.of("Basic computed-for-" + as));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void sendsTheCallersComputedAuthorization() throws Exception {
        assertThat(client.send(RestCall.get("alice", "/")).text()).isEqualTo("Basic computed-for-alice");
        assertThat(client.send(RestCall.get("anonymous", "/")).text()).isEmpty();
    }

    @Test
    void anAuthorizationHeaderOfTheCallReplacesTheComputedOne() throws Exception {
        RestCall call = new RestCall(
                "alice", "GET", "/", Map.of("authorization", "Bearer raw-token"), Optional.empty(), Optional.empty());
        assertThat(client.send(call).text()).isEqualTo("Bearer raw-token");
    }

    @Test
    void aCookieSessionIsSentAsGivenWithoutTheComputedAuthorization() throws Exception {
        Map<String, String> session = RestCall.get("carol", "/")
                .withHeader("Cookie", "wordpress_logged_in_x=carol|1|t|h")
                .withHeader("X-WP-Nonce", "0123456789")
                .headers();
        assertThat(client.send(session("/echo/Cookie", session)).text()).isEqualTo("wordpress_logged_in_x=carol|1|t|h");
        assertThat(client.send(session("/echo/X-WP-Nonce", session)).text()).isEqualTo("0123456789");
        assertThat(client.send(session("/", session)).text())
                .as("no application password next to a cookie session")
                .isEmpty();
    }

    static RestCall session(String target, Map<String, String> headers) {
        return new RestCall("carol", "GET", target, headers, Optional.empty(), Optional.empty());
    }

    @Test
    void withHeaderReplacesTheSameNameWithoutCase() {
        RestCall call = RestCall.get("alice", "/").withHeader("Accept", "a").withHeader("accept", "b");
        assertThat(call.headers()).containsExactlyEntriesOf(Map.of("accept", "b"));
    }

    @Test
    void headerLinesAreSentOneLinePerValueInOrder() throws Exception {
        Map<String, List<String>> lines = new LinkedHashMap<>();
        lines.put("Content-Disposition", List.of("attachment; filename=\"a.png\"", "attachment; filename=\"b.png\""));
        RestCall call = RestCall.raw("alice", "/echo/Content-Disposition", Optional.empty(), lines, new byte[] {1, 2});
        assertThat(client.send(call).text())
                .isEqualTo("attachment; filename=\"a.png\"\nattachment; filename=\"b.png\"");
        assertThat(client.send(call.withHeader("X-Other", "x")).text())
                .as("withHeader keeps the header lines")
                .isEqualTo("attachment; filename=\"a.png\"\nattachment; filename=\"b.png\"");
    }

    @Test
    void aRawCallWithoutAContentTypeSendsNone() throws Exception {
        RestCall call = RestCall.raw("alice", "/echo/Content-Type", Optional.empty(), Map.of(), new byte[] {1});
        assertThat(client.send(call).text()).isEmpty();
        RestCall typed =
                RestCall.raw("alice", "/echo/Content-Type", Optional.of("image/png"), Map.of(), new byte[] {1});
        assertThat(client.send(typed).text()).isEqualTo("image/png");
    }

    @Test
    void aRawCallIsAPostOfItsBodyAsIsWithTheLinesKeptApartFromTheHeaders() {
        byte[] body = {0, (byte) 0xFF, '\r', '\n'};
        Map<String, List<String>> lines = new LinkedHashMap<>();
        lines.put("Z-Last", List.of("1"));
        lines.put("A-First", List.of("2", "3"));
        RestCall call = RestCall.raw("alice", "/x", Optional.of("image/png"), lines, body);
        body[0] = 9;
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.body()).hasValueSatisfying(sent -> assertThat(sent).containsExactly(0, 0xFF, '\r', '\n'));
        assertThat(call.headers()).isEmpty();
        assertThat(call.headerLines().keySet()).containsExactly("Z-Last", "A-First");
        assertThat(call.headerLines().get("A-First")).containsExactly("2", "3");
        assertThat(RestCall.get("alice", "/").headerLines()).isEmpty();
        assertThat(new RestCall("alice", "GET", "/", Map.of(), Optional.empty(), Optional.empty()).headerLines())
                .isEmpty();
    }

    @Test
    void aCookieOrAuthorizationHeaderLineReplacesTheComputedCredential() throws Exception {
        RestCall bearer = RestCall.raw(
                "alice", "/", Optional.empty(), Map.of("Authorization", List.of("Bearer line-token")), new byte[] {1});
        assertThat(client.send(bearer).text()).isEqualTo("Bearer line-token");
        RestCall cookie = RestCall.raw(
                "alice", "/", Optional.empty(), Map.of("Cookie", List.of("wordpress_logged_in_x=a")), new byte[] {1});
        assertThat(client.send(cookie).text())
                .as("no application password next to a cookie line")
                .isEmpty();
    }

    @Test
    void movingACallUnderASiteKeepsItsHeaderLines() {
        Map<String, List<String>> lines = new LinkedHashMap<>();
        lines.put("Content-Disposition", List.of("attachment; filename=\"a.png\"", "attachment; filename=\"b.png\""));
        RestCall moved = Side.underSite(
                "/es01/",
                RestCall.raw("alice", "/wp-json/wp/v2/media", Optional.of("image/png"), lines, new byte[] {1}));
        assertThat(moved.target()).isEqualTo("/es01/wp-json/wp/v2/media");
        assertThat(moved.headerLines()).containsExactlyEntriesOf(lines);
    }

    @Test
    void redirectsAreNotFollowed() throws Exception {
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://localhost/wp-admin/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        Exchange exchange = client.send(RestCall.get("anonymous", "/redirect"));
        assertThat(exchange.status()).isEqualTo(302);
        assertThat(exchange.header("Location")).contains("http://localhost/wp-admin/");
    }
}
