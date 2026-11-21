package org.quarkpress.it.keycloak;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.quarkpress.it.keycloak.WordPressHashes.Row;

/**
 * Users imported with their WordPress password hashes sign in end to end: {@code UserImports} creates them in Dev
 * Services' Keycloak through the provisioner (a client-credentials admin client, as in production), Keycloak verifies
 * the old password with the quarkpress-keycloak provider mounted into it, QuarkPress accepts the token and serves the
 * imported profile, and Keycloak re-hashes the password with Argon2 on the first sign-in. JVM mode here, the packaged
 * application in {@code KeycloakE2EIT}, a native executable in CI. Skipped when the provider jar is missing.
 */
@QuarkusTest
@WithTestResource(ProvisionerClient.class)
@ExtendWith(ProviderJarCondition.class)
class KeycloakE2EDbTest {

    /** WordPress-made hashes of every form QuarkPress accepts; the -quote rows hash O'Brien in its slashed form. */
    static Stream<String> acceptedForms() {
        return Stream.of(
                "wp-ascii",
                "wp-utf8",
                "wp-quote",
                "phpass-ascii",
                "phpass-utf8",
                "phpass-quote",
                "md5-ascii",
                "md5-quote",
                "bcrypt-ascii",
                "bcrypt-quote",
                "bcrypt2a-ascii",
                "bcrypt2b-ascii");
    }

    private static final String ARGON2 = "argon2";

    private static final String WORDPRESS = "wordpress";

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    /** Obtains tokens from the Dev Services Keycloak (in the IT, Quarkus hands it the Dev Services context). */
    KeycloakTestClient keycloak = new KeycloakTestClient();

    /** A Keycloak answer to a password grant. */
    record Grant(int status, JsonNode body) {

        String accessToken() {
            return body.path("access_token").asText();
        }

        @Override
        public String toString() {
            return status + " " + body.path("error").asText() + " "
                    + body.path("error_description").asText();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedForms")
    void anImportedUserSignsInWithTheWordPressPasswordWhichMovesToArgon2(String id) {
        Row row = WordPressHashes.row(id);
        String username = username(id);
        String displayName = "Imported " + id;

        JsonPath imported = importUser(username, username + "@example.com", displayName, "Imported", "User", row.hash())
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(imported.getBoolean("createdInIdentityProvider")).isTrue();
        assertThat(imported.getBoolean("profileCreated")).isTrue();
        String subject = imported.getString("subject");
        long userId = imported.getLong("userId");
        assertThat(imported.getString("nicename")).isEqualTo(username);
        Map<String, String> stored = onlyPasswordCredential(subject);
        assertThat(stored.get("algorithm")).isEqualTo(WORDPRESS);

        // a wrong password is refused and changes nothing
        assertInvalidCredentials(grant(username, row.password() + "!"));
        assertThat(onlyPasswordCredential(subject)).isEqualTo(stored);

        Grant first = grant(username, row.password());
        assertThat(first.status()).as("%s", first).isEqualTo(200);
        // the token signs the imported profile in: the sign-in neither renames it nor makes another
        assertMe(first.accessToken(), userId, displayName, username);

        Map<String, String> migrated = awaitAlgorithm(subject, ARGON2);
        assertThat(migrated.get("id")).as("the same credential, re-hashed").isEqualTo(stored.get("id"));
        assertMadeAsKeycloakMakesNewPasswords(migrated);

        Grant second = grant(username, row.password());
        assertThat(second.status()).as("%s", second).isEqualTo(200);
        assertMe(second.accessToken(), userId, displayName, username);
        assertInvalidCredentials(grant(username, row.password() + "!"));
        assertThat(onlyPasswordCredential(subject)).isEqualTo(migrated);

        // importing the username again, with another hash, returns what exists and keeps the Argon2 credential
        JsonPath again = importUser(
                        username,
                        username + "@example.com",
                        "Somebody Else",
                        null,
                        null,
                        WordPressHashes.row("wp-long100").hash())
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(again.getBoolean("createdInIdentityProvider")).isFalse();
        assertThat(again.getBoolean("profileCreated")).isFalse();
        assertThat(again.getString("subject")).isEqualTo(subject);
        assertThat(again.getLong("userId")).isEqualTo(userId);
        assertThat(again.getString("displayName")).isEqualTo(displayName);
        assertThat(onlyPasswordCredential(subject)).isEqualTo(migrated);
        assertThat(grant(username, row.password()).status()).isEqualTo(200);
    }

    /**
     * Dev Services' user profile makes e-mail, first and last name optional, so a user imported without them signs in
     * here; a realm with Keycloak's default user profile answers "Account is not fully set up" instead.
     */
    @Test
    void aUserWithoutNamesOrEmailSignsInUnderDevServicesUserProfile() {
        Row row = WordPressHashes.row("md5-ascii");
        String username = username("bare");

        JsonPath imported = importUser(username, null, "Bare User", null, null, row.hash())
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(imported.getBoolean("createdInIdentityProvider")).isTrue();

        Grant grant = grant(username, row.password());
        assertThat(grant.status()).as("%s", grant).isEqualTo(200);
        assertMe(grant.accessToken(), imported.getLong("userId"), "Bare User", username);
        assertThat(awaitAlgorithm(imported.getString("subject"), ARGON2).get("algorithm"))
                .isEqualTo(ARGON2);
    }

    /**
     * Setting the provisioner's client up again, as a restart of the application on the same Keycloak does, reuses the
     * client and its secret: the running application's admin client keeps working.
     */
    @Test
    void settingTheProvisionersClientUpAgainReusesIt() {
        Map<String, String> again = ProvisionerClient.setUp(keycloak);
        Map<String, String> third = ProvisionerClient.setUp(keycloak);

        assertThat(again.get("quarkus.keycloak.admin-client.client-secret")).isNotBlank();
        assertThat(third).isEqualTo(again);
        assertThat(importUser(username("again"), null, "Again", null, null, null)
                        .statusCode(200)
                        .extract()
                        .jsonPath()
                        .getBoolean("createdInIdentityProvider"))
                .isTrue();
    }

    private io.restassured.response.ValidatableResponse importUser(
            String username, String email, String displayName, String firstName, String lastName, String hash) {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("email", email);
        body.put("displayName", displayName);
        body.put("firstName", firstName);
        body.put("lastName", lastName);
        body.put("hash", hash);
        return asAlice()
                .contentType(ContentType.JSON)
                .body(body)
                .post("/it/import")
                .then();
    }

    private io.restassured.specification.RequestSpecification asAlice() {
        return given().header("Host", "localhost").auth().oauth2(keycloak.getAccessToken("alice"));
    }

    private void assertMe(String token, long userId, String name, String slug) {
        JsonPath me = given().header("Host", "localhost")
                .auth()
                .oauth2(token)
                .get("/wp-json/wp/v2/users/me")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(me.getLong("id")).isEqualTo(userId);
        assertThat(me.getString("name")).isEqualTo(name);
        assertThat(me.getString("slug")).isEqualTo(slug);
    }

    /** The user's only credential, a password: id, type, algorithm and credential data (never its secret data). */
    private Map<String, String> onlyPasswordCredential(String subject) {
        List<Map<String, String>> credentials = asAlice()
                .get("/it/credentials/" + subject)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("$");
        assertThat(credentials).hasSize(1);
        assertThat(credentials.get(0).get("type")).isEqualTo("password");
        return credentials.get(0);
    }

    /** The password credential once Keycloak has re-hashed it, which it does after the sign-in's transaction. */
    private Map<String, String> awaitAlgorithm(String subject, String algorithm) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (true) {
            Map<String, String> credential = onlyPasswordCredential(subject);
            if (algorithm.equals(credential.get("algorithm")) || System.nanoTime() > deadline) {
                assertThat(credential.get("algorithm")).isEqualTo(algorithm);
                return credential;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * The re-hashed credential carries the same credential data (iterations, algorithm and its parameters) as one
     * Keycloak makes itself for a new password in the realm: nothing of the import's iterations 0 and empty algorithm
     * data survives.
     */
    private void assertMadeAsKeycloakMakesNewPasswords(Map<String, String> migrated) {
        String reference = asAlice()
                .contentType(ContentType.JSON)
                .body(Map.of("username", username("reference"), "password", "a reference password"))
                .post("/it/reference-users")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        JsonNode expected = read(onlyPasswordCredential(reference).get("credentialData"));
        JsonNode actual = read(migrated.get("credentialData"));
        assertThat(expected.path("algorithm").asText()).isEqualTo(ARGON2);
        assertThat(expected.path("hashIterations").asInt()).isPositive();
        assertThat(actual).as("credential data").isEqualTo(expected);
    }

    private static void assertInvalidCredentials(Grant grant) {
        assertThat(grant.status()).as("%s", grant).isEqualTo(400);
        assertThat(grant.body().path("error").asText()).isEqualTo("invalid_grant");
        assertThat(grant.body().path("error_description").asText()).isEqualTo("Invalid user credentials");
    }

    /** The password grant on the dev realm's client, the password sent as UTF-8. */
    private Grant grant(String username, String password) {
        StringJoiner form = new StringJoiner("&");
        Map.of(
                        "grant_type", "password",
                        "client_id", "quarkus-app",
                        "client_secret", "secret",
                        "username", username,
                        "password", password)
                .forEach((key, value) -> form.add(encode(key) + "=" + encode(value)));
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(keycloak.getAuthServerUrl() + "/protocol/openid-connect/token"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                .build();
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            return new Grant(response.statusCode(), read(response.body()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode read(String json) {
        try {
            return JSON.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** A Keycloak username no other test uses: lower-case, as Keycloak stores it. */
    private static String username(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
