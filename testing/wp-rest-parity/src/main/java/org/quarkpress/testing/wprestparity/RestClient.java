package org.quarkpress.testing.wprestparity;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Sends {@link RestCall}s to a server over HTTP/1.1 with {@code java.net.http}, following no redirects, and records
 * what came back. Any method goes, HEAD and OPTIONS included.
 */
public final class RestClient {

    /** The user {@code "invalid"} sends: alice's login with a password no server accepts. */
    static final String WRONG_PASSWORD = "not-the-application-password";

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final String root;
    private final Function<String, Optional<String>> authorization;
    private final HttpClient http;

    /**
     * {@code authorization} maps {@link RestCall#as()} to an {@code Authorization} header value; empty for anonymous.
     * A call that names its own {@code Authorization} header sends that one instead.
     */
    public RestClient(URI root, Function<String, Optional<String>> authorization) {
        String text = Objects.requireNonNull(root, "root").toString();
        this.root = text.endsWith("/") ? text.substring(0, text.length() - 1) : text;
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public Exchange send(RestCall call) throws IOException, InterruptedException {
        HttpRequest.BodyPublisher body =
                call.body().map(HttpRequest.BodyPublishers::ofByteArray).orElseGet(HttpRequest.BodyPublishers::noBody);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(root + call.target()))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(TIMEOUT)
                .method(call.method(), body);
        boolean ownAuthorization = false;
        for (String name : call.headers().keySet()) {
            ownAuthorization |= name.equalsIgnoreCase("Authorization");
        }
        if (!ownAuthorization) {
            authorization.apply(call.as()).ifPresent(value -> request.header("Authorization", value));
        }
        call.contentType().ifPresent(type -> request.header("Content-Type", type));
        for (Map.Entry<String, String> header : call.headers().entrySet()) {
            request.header(header.getKey(), header.getValue());
        }
        HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Exchange(call, response.statusCode(), response.headers().map(), response.body());
    }

    /**
     * Basic authentication with the site's application passwords: a {@link WordPressRestSite#USERS} entry sends its
     * own, {@code "invalid"} sends alice's login with a wrong password, {@code "anonymous"} sends nothing.
     */
    public static Function<String, Optional<String>> applicationPasswords(WordPressRestSite site) {
        Objects.requireNonNull(site, "site");
        return as -> switch (as) {
            case "anonymous" -> Optional.empty();
            case "invalid" -> Optional.of(basic("alice", WRONG_PASSWORD));
            default -> {
                if (!WordPressRestSite.USERS.contains(as)) {
                    throw new IllegalArgumentException("unknown caller '" + as + "'; known: anonymous, invalid, "
                            + String.join(", ", WordPressRestSite.USERS));
                }
                yield Optional.of(basic(as, site.applicationPassword(as)));
            }
        };
    }

    static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
