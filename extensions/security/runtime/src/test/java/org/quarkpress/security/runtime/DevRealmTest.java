package org.quarkpress.security.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class DevRealmTest {

    private static JsonNode realm() throws Exception {
        try (InputStream in = DevRealmTest.class.getClassLoader().getResourceAsStream("quarkpress-dev-realm.json")) {
            return new ObjectMapper().readTree(in);
        }
    }

    @Test
    void theRealmHasTheClientDevServicesAndTestsUse() throws Exception {
        JsonNode realm = realm();
        assertThat(realm.get("realm").asText()).isEqualTo("quarkpress");
        JsonNode client = realm.get("clients").get(0);
        assertThat(client.get("clientId").asText()).isEqualTo("quarkus-app");
        assertThat(client.get("secret").asText()).isEqualTo("secret");
        assertThat(client.get("directAccessGrantsEnabled").asBoolean()).isTrue();
        List<String> claims = new ArrayList<>();
        for (JsonNode mapper : client.get("protocolMappers")) {
            assertThat(mapper.get("config").get("access.token.claim").asText()).isEqualTo("true");
            claims.add(mapper.get("config").get("claim.name").asText());
        }
        assertThat(claims).containsExactlyInAnyOrder("preferred_username", "given_name", "family_name");
    }

    @Test
    void theUsersAreTheDevUsersWithFixedIdsAndTheirNameAsPassword() throws Exception {
        JsonNode users = realm().get("users");
        assertThat(users).hasSize(DevUsers.ALL.size());
        for (int i = 0; i < DevUsers.ALL.size(); i++) {
            DevUsers.DevUser expected = DevUsers.ALL.get(i);
            JsonNode user = users.get(i);
            assertThat(user.get("id").asText()).isEqualTo(expected.id());
            assertThat(user.get("username").asText()).isEqualTo(expected.username());
            assertThat(user.get("firstName").asText()).isEqualTo(expected.givenName());
            assertThat(user.get("lastName").asText()).isEqualTo(expected.familyName());
            assertThat(user.get("credentials").get(0).get("value").asText()).isEqualTo(expected.username());
        }
        assertThat(DevUsers.ALL)
                .extracting(DevUsers.DevUser::username)
                .containsExactly("alice", "bob", "carol", "dave", "erin");
    }

    @Test
    void devServicesImportTheRealm() throws Exception {
        Properties properties = new Properties();
        try (InputStream in =
                DevRealmTest.class.getClassLoader().getResourceAsStream("META-INF/microprofile-config.properties")) {
            properties.load(in);
        }
        assertThat(properties.getProperty("quarkus.keycloak.devservices.realm-path"))
                .isEqualTo("quarkpress-dev-realm.json");
        assertThat(properties.getProperty("quarkus.keycloak.devservices.realm-name"))
                .isEqualTo("quarkpress");
    }
}
