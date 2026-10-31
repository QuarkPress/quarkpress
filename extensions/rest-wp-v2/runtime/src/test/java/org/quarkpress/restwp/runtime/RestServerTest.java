package org.quarkpress.restwp.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.quarkpress.restwp.RestError;

class RestServerTest {

    static final String FATAL = "{\"code\":\"internal_server_error\",\"message\":\"There has been a critical error on"
            + " this website.\",\"data\":{\"status\":500}}";

    @Test
    void everyFailureOnTheWayEndsAsWordPresssFatalErrorObject() {
        List<Supplier<RuntimeException>> runtime =
                List.of(() -> new IllegalStateException("database down"), () -> new NullPointerException("site"));
        for (Supplier<RuntimeException> failure : runtime) {
            assertFatal(failingGate(() -> {
                throw failure.get();
            }));
        }
        assertFatal(failingGate(() -> {
            throw new StackOverflowError();
        }));
    }

    private static void assertFatal(RestServer server) {
        IncomingRequest request = request(Map.of());
        for (ServedResponse served : List.of(
                server.serve(request),
                server.serveAuthFailure(request, RestError.of("rest_invalid_token", "x", 401)),
                server.refuse(request, "rest_path_invalid_type", "The REST route parameter must be a string."))) {
            assertThat(served.status()).isEqualTo(500);
            assertThat(served.headers()).containsEntry("Content-Type", List.of("application/json; charset=UTF-8"));
            assertThat(new String(served.body(), StandardCharsets.UTF_8)).isEqualTo(FATAL);
        }
    }

    @Test
    void theRedirectTargetAlwaysStartsTheSitesPath() {
        URI site = URI.create("http://localhost/news/");
        assertThat(SiteGate.location(site, "/wp-json/x?a=1")).isEqualTo("http://localhost/news/wp-json/x?a=1");
        assertThat(SiteGate.location(site, "@evil.test/x")).isEqualTo("http://localhost/news/@evil.test/x");
        assertThat(SiteGate.location(URI.create("http://localhost"), ".evil.test/x"))
                .isEqualTo("http://localhost/.evil.test/x");
        assertThat(SiteGate.location(URI.create("http://localhost"), "/x")).isEqualTo("http://localhost/x");
    }

    @Test
    void repeatedAcceptHeadersAreReadJoinedAsPhpHoldsThem() {
        assertThat(SiteGate.isJson(request(Map.of("Accept", List.of("text/html", "application/json")))))
                .isTrue();
        assertThat(SiteGate.isJson(request(Map.of("Content-Type", List.of("text/plain", "application/json")))))
                .isTrue();
        assertThat(SiteGate.isJson(request(Map.of("Accept", List.of("text/html", "text/plain")))))
                .isFalse();
    }

    private static RestServer failingGate(Runnable failure) {
        RestServer server = new RestServer();
        server.gate = new SiteGate() {
            @Override
            public Optional<ServedResponse> check(IncomingRequest request) {
                failure.run();
                return Optional.empty();
            }

            @Override
            public Optional<Gate> decide(IncomingRequest request) {
                failure.run();
                return Optional.empty();
            }
        };
        return server;
    }

    private static IncomingRequest request(Map<String, List<String>> headers) {
        return new IncomingRequest(
                "GET", "/x", RestJson.NODES.objectNode(), headers, new byte[0], RestJson.NODES.objectNode());
    }
}
