package org.quarkpress.testing.wprestparity;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
