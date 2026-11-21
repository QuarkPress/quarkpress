package org.quarkpress.it.keycloak;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.common.DevServicesContext;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Sets the provisioner up as production does (its README's recipe): a confidential client of the dev realm whose
 * service account holds only the realm-management roles {@code manage-users} and {@code view-users}, used through the
 * client-credentials grant. Dev Services' own admin ({@code admin}/{@code admin} in {@code master}) creates the client
 * before the application starts; the packaged application refuses to start on those default credentials.
 *
 * <p>The client's secret is a random value made for each run, for a client that lives only in Dev Services' throwaway
 * container: the integration-test launcher logs the application's command line, secret included. Never pass a real
 * secret this way. A client left by an earlier start on the same Keycloak is reused with its secret.
 */
public class ProvisionerClient implements QuarkusTestResourceLifecycleManager, DevServicesContext.ContextAware {

    static final String REALM = "quarkpress";

    static final String CLIENT_ID = "quarkpress-provisioner";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    private DevServicesContext context;

    @Override
    public void setIntegrationTestContext(DevServicesContext context) {
        this.context = context;
    }

    @Override
    public Map<String, String> start() {
        if (!ProviderJarCondition.problems().isEmpty()) {
            // the end-to-end tests are skipped: nothing to set up
            return Map.of();
        }
        KeycloakTestClient keycloak = new KeycloakTestClient();
        if (context != null) {
            keycloak.setIntegrationTestContext(context);
        }
        return setUp(keycloak);
    }

    /**
     * Creates the client, or reuses the one an earlier call made, on the Keycloak {@code keycloak} reaches; answers the
     * application's admin-client configuration.
     */
    static Map<String, String> setUp(KeycloakTestClient keycloak) {
        String admin = keycloak.getAdminAccessToken();
        String realm = keycloak.getAuthServerBaseUrl() + "/admin/realms/" + REALM;
        String clientUuid;
        String secret;
        JsonNode existing = send(admin, "GET", realm + "/clients?clientId=" + encode(CLIENT_ID), null, 200);
        if (existing.isEmpty()) {
            secret = UUID.randomUUID().toString();
            ObjectNode client = JSON.createObjectNode()
                    .put("clientId", CLIENT_ID)
                    .put("enabled", true)
                    .put("publicClient", false)
                    .put("secret", secret)
                    .put("serviceAccountsEnabled", true)
                    .put("standardFlowEnabled", false)
                    .put("directAccessGrantsEnabled", false);
            send(admin, "POST", realm + "/clients", client, 201);
            clientUuid = send(admin, "GET", realm + "/clients?clientId=" + encode(CLIENT_ID), null, 200)
                    .get(0)
                    .path("id")
                    .asText();
        } else {
            // an earlier start on this Keycloak made it: reuse it, so that a running application keeps its secret
            clientUuid = existing.get(0).path("id").asText();
            secret = send(admin, "GET", realm + "/clients/" + clientUuid + "/client-secret", null, 200)
                    .path("value")
                    .asText();
        }
        String serviceAccount = send(
                        admin, "GET", realm + "/clients/" + clientUuid + "/service-account-user", null, 200)
                .path("id")
                .asText();
        String management = send(admin, "GET", realm + "/clients?clientId=realm-management", null, 200)
                .get(0)
                .path("id")
                .asText();
        ArrayNode roles = JSON.createArrayNode();
        for (String role : new String[] {"manage-users", "view-users"}) {
            roles.add(send(admin, "GET", realm + "/clients/" + management + "/roles/" + role, null, 200));
        }
        send(admin, "POST", realm + "/users/" + serviceAccount + "/role-mappings/clients/" + management, roles, 204);

        return Map.of(
                "quarkus.keycloak.admin-client.realm", REALM,
                "quarkus.keycloak.admin-client.grant-type", "CLIENT_CREDENTIALS",
                "quarkus.keycloak.admin-client.client-id", CLIENT_ID,
                "quarkus.keycloak.admin-client.client-secret", secret,
                "quarkpress.keycloak-provisioner.realm", REALM);
    }

    @Override
    public void stop() {
        // the client goes with Dev Services' Keycloak
    }

    private static JsonNode send(String token, String method, String url, JsonNode body, int expected) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        }
        try {
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != expected) {
                throw new IllegalStateException("Keycloak answered " + response.statusCode() + " to " + method + " "
                        + url + ": " + response.body());
            }
            return response.body().isEmpty() ? JSON.nullNode() : JSON.readTree(response.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }
}
