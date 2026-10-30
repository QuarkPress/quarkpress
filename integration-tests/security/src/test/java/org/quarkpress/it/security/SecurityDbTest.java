package org.quarkpress.it.security;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.quarkpress.security.runtime.DevUsers;

@QuarkusTest
class SecurityDbTest {

    /** Obtains tokens from the Dev Services Keycloak (in the IT, Quarkus hands it the Dev Services context). */
    KeycloakTestClient keycloak = new KeycloakTestClient();

    /** A site created for one test, on its own mapped domain. */
    private record TestSite(long id, String host) {}

    private RequestSpecification as(String user, String host) {
        RequestSpecification request = given().header("Host", host);
        return user == null ? request : request.auth().oauth2(keycloak.getAccessToken(user));
    }

    private RequestSpecification withToken(String token, String host) {
        return given().header("Host", host).auth().oauth2(token);
    }

    private long userId(String user) {
        return as(user, "localhost")
                .get("/it/me")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("user");
    }

    private static String uniqueHost(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + ".example";
    }

    /** Asks for a new site on {@code host}, as {@code user}; returns the HTTP status. */
    private int createSite(String user, String host) {
        return as(user, "localhost")
                .contentType(ContentType.JSON)
                .body(Map.of("domain", host, "path", "/", "title", host))
                .post("/it/network/sites")
                .statusCode();
    }

    /** A new site on its own mapped domain, created by alice. */
    private TestSite newSite() {
        String host = uniqueHost("s");
        long id = as("alice", "localhost")
                .contentType(ContentType.JSON)
                .body(Map.of("domain", host, "path", "/", "title", host))
                .post("/it/network/sites")
                .then()
                .statusCode(200)
                .extract()
                .as(Long.class);
        return new TestSite(id, host);
    }

    private void member(long site, String user, String role) {
        as("alice", "localhost")
                .contentType(ContentType.JSON)
                .body(Map.of("site", site, "user", userId(user), "role", role))
                .post("/it/memberships")
                .then()
                .statusCode(200);
    }

    /** Asks, as {@code caller} on {@code host}, to give {@code user} the role on {@code site}; returns the status. */
    private int addMember(String caller, String host, long site, long user, String role) {
        return as(caller, host)
                .contentType(ContentType.JSON)
                .body(Map.of("site", site, "user", user, "role", role))
                .post("/it/memberships")
                .statusCode();
    }

    private int status(String user, String host, String method, String path) {
        return method.equals("POST")
                ? as(user, host).post(path).statusCode()
                : as(user, host).get(path).statusCode();
    }

    private boolean can(String user, String host, String capability) {
        return as(user, host)
                .get("/it/can/" + capability)
                .then()
                .statusCode(200)
                .extract()
                .as(Boolean.class);
    }

    private boolean canForUser(String user, String host, String capability, long target) {
        return as(user, host)
                .get("/it/can/" + capability + "/user/" + target)
                .then()
                .statusCode(200)
                .extract()
                .as(Boolean.class);
    }

    private List<String> roles(String user, String host) {
        return as(user, host)
                .get("/it/me")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("roles", String.class);
    }

    private boolean superAdmin(String user) {
        return as(user, "localhost")
                .get("/it/me")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getBoolean("superAdmin");
    }

    private static String part(String token, int index) {
        return token.split("\\.")[index];
    }

    private static JsonPath claims(String token) {
        return JsonPath.from(new String(Base64.getUrlDecoder().decode(part(token, 1)), StandardCharsets.UTF_8));
    }

    /** The token's payload: the base64url-decoded middle part of the JWT. */
    private static JsonNode payload(String token) {
        try {
            return new ObjectMapper().readTree(Base64.getUrlDecoder().decode(part(token, 1)));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encode(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void devRealmTokensCarryTheVerifiedEmail() {
        JsonNode claims = payload(keycloak.getAccessToken("carol"));
        assertThat(claims.get("email")).isEqualTo(TextNode.valueOf("carol@quarkpress.test"));
        assertThat(claims.get("email_verified")).isEqualTo(BooleanNode.TRUE);
    }

    @Test
    void theDevRealmIssuesTokensWithEachUsersFixedIdAndNames() {
        for (DevUsers.DevUser user : DevUsers.ALL) {
            JsonPath claims = claims(keycloak.getAccessToken(user.username()));
            assertThat(claims.getString("sub")).as(user.username()).isEqualTo(user.id());
            assertThat(claims.getString("preferred_username"))
                    .as(user.username())
                    .isEqualTo(user.username());
            assertThat(claims.getString("given_name")).as(user.username()).isEqualTo(user.givenName());
            assertThat(claims.getString("family_name")).as(user.username()).isEqualTo(user.familyName());
        }
    }

    @Test
    void realmRolesInTheTokenNeverBecomeSiteRoles() {
        // bob holds the realm role "administrator", named like WordPress's role: the strongest leak probe
        assertThat(claims(keycloak.getAccessToken("bob")).getList("realm_access.roles", String.class))
                .contains("administrator");
        assertThat(roles("bob", "localhost")).isEmpty();
        assertThat(can("bob", "localhost", "manage_options")).isFalse();
        assertThat(status("bob", "localhost", "POST", "/it/settings")).isEqualTo(403);
        TestSite site = newSite();
        member(site.id(), "bob", "editor");
        assertThat(roles("bob", site.host())).isEqualTo(List.of("editor"));
        assertThat(can("bob", site.host(), "manage_options")).isFalse();
        assertThat(status("bob", site.host(), "POST", "/it/settings")).isEqualTo(403);
        // on a site where bob is an administrator, the role comes from his membership
        TestSite administered = newSite();
        member(administered.id(), "bob", "administrator");
        assertThat(roles("bob", administered.host())).isEqualTo(List.of("administrator"));
        assertThat(status("bob", administered.host(), "POST", "/it/settings")).isEqualTo(200);
    }

    @Test
    void aTokenWithAlteredClaimsOrSignatureIsRefused() {
        String bob = keycloak.getAccessToken("bob");
        String alice = keycloak.getAccessToken("alice");
        // bob's signed token, with alice's subject put in its payload
        String altered = part(bob, 0)
                + "."
                + encode(claims(bob)
                        .prettify()
                        .replace(claims(bob).getString("sub"), claims(alice).getString("sub")))
                + "."
                + part(bob, 2);
        assertThat(claims(altered).getString("sub"))
                .isEqualTo(claims(alice).getString("sub"))
                .isNotEqualTo(claims(bob).getString("sub"));
        // alice's token with one character of its signature changed
        String signature = part(alice, 2);
        int middle = signature.length() / 2;
        String flipped = signature.substring(0, middle)
                + (signature.charAt(middle) == 'A' ? 'B' : 'A')
                + signature.substring(middle + 1);
        String badSignature = part(alice, 0) + "." + part(alice, 1) + "." + flipped;
        // alice's claims, unsigned
        String unsigned = encode("{\"alg\":\"none\",\"typ\":\"JWT\"}") + "." + part(alice, 1) + ".";
        for (String forged : List.of(altered, badSignature, unsigned)) {
            withToken(forged, "localhost").get("/it/me").then().statusCode(401);
            String host = uniqueHost("f");
            assertThat(withToken(forged, "localhost")
                            .contentType(ContentType.JSON)
                            .body(Map.of("domain", host, "path", "/", "title", host))
                            .post("/it/network/sites")
                            .statusCode())
                    .isEqualTo(401);
            // nothing was created: the domain is still free
            assertThat(createSite("alice", host)).isEqualTo(200);
        }
    }

    @Test
    void anonymousVisitorsHoldOnlyExist() {
        assertThat(can(null, "localhost", "exist")).isTrue();
        assertThat(can(null, "localhost", "read")).isFalse();
        assertThat(status(null, "localhost", "POST", "/it/drafts")).isEqualTo(401);
    }

    @Test
    void aProfileCarriesTheTokensClaims() {
        JsonPath carol = as("carol", "localhost")
                .get("/it/me")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(carol.getString("nicename")).isEqualTo("carol");
        assertThat(carol.getString("displayName")).isEqualTo("Carol Chen");
        assertThat(carol.getString("subject")).isEqualTo("ca401000-0000-4000-8000-000000000003");
        assertThat(carol.getBoolean("superAdmin")).isFalse();
        JsonPath alice = as("alice", "localhost")
                .get("/it/me")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(alice.getBoolean("superAdmin")).isTrue();
        // alice's profile was created at startup, before her first login: no claims, so the id names it
        assertThat(alice.getString("nicename")).isEqualTo("user-" + alice.getLong("user"));
    }

    @Test
    void theRequestSitesRolesGateSecuredMethods() {
        TestSite site = newSite();
        member(site.id(), "bob", "editor");
        member(site.id(), "erin", "subscriber");
        assertThat(roles("bob", site.host())).isEqualTo(List.of("editor"));
        assertThat(status("bob", site.host(), "POST", "/it/drafts")).isEqualTo(200);
        assertThat(status("bob", site.host(), "POST", "/it/settings")).isEqualTo(403);
        assertThat(status("erin", site.host(), "POST", "/it/drafts")).isEqualTo(403);
        assertThat(roles("bob", "localhost")).isEmpty();
        assertThat(status("bob", "localhost", "POST", "/it/drafts")).isEqualTo(403);
    }

    @Test
    void aMetaCapabilityLooksAtThePost() {
        TestSite site = newSite();
        member(site.id(), "carol", "author");
        member(site.id(), "dave", "contributor");
        long carol = userId("carol");
        long dave = userId("dave");
        long bob = userId("bob");
        assertThat(edit("carol", site.host(), carol, "publish")).isEqualTo(200);
        assertThat(edit("carol", site.host(), bob, "draft")).isEqualTo(403);
        assertThat(edit("dave", site.host(), dave, "draft")).isEqualTo(200);
        assertThat(edit("dave", site.host(), dave, "publish")).isEqualTo(403);
    }

    private int edit(String user, String host, long author, String status) {
        return as(user, host)
                .contentType(ContentType.JSON)
                .body(Map.of("author", author, "status", status))
                .post("/it/posts/edit")
                .statusCode();
    }

    @Test
    void networkAdministratorsPassAndSiteAdministratorsAreRestricted() {
        TestSite site = newSite();
        member(site.id(), "bob", "administrator");
        assertThat(can("alice", site.host(), "manage_options")).isTrue();
        assertThat(can("alice", site.host(), "unfiltered_html")).isTrue();
        assertThat(can("bob", site.host(), "manage_options")).isTrue();
        assertThat(can("bob", site.host(), "unfiltered_html")).isFalse();
        assertThat(can("bob", site.host(), "update_core")).isFalse();
        long erin = userId("erin");
        assertThat(canForUser("bob", site.host(), "promote_user", erin)).isTrue();
        assertThat(canForUser("bob", site.host(), "edit_user", erin)).isFalse();
    }

    @Test
    void networkServicesNeedNetworkCapabilities() {
        String host = uniqueHost("n");
        assertThat(createSite("bob", host)).isEqualTo(403);
        as(null, "localhost")
                .contentType(ContentType.JSON)
                .body(Map.of("domain", host, "path", "/", "title", host))
                .post("/it/network/sites")
                .then()
                .statusCode(401)
                .header("WWW-Authenticate", "Bearer");
        // neither refused call created the site: the domain is still free
        assertThat(createSite("alice", host)).isEqualTo(200);
    }

    @Test
    void membershipsNeedPromoteUserOnTheSite() {
        TestSite site = newSite();
        member(site.id(), "carol", "author");
        long erin = userId("erin");
        assertThat(addMember("carol", site.host(), site.id(), erin, "editor")).isEqualTo(403);
        assertThat(can("erin", site.host(), "read")).isFalse();
        assertThat(roles("erin", site.host())).isEmpty();
        member(site.id(), "erin", "subscriber");
        assertThat(can("erin", site.host(), "read")).isTrue();
        as("alice", "localhost")
                .delete("/it/memberships/" + site.id() + "/" + erin)
                .then()
                .statusCode(200);
        assertThat(can("erin", site.host(), "read")).isFalse();
    }

    @Test
    void aSiteAdministratorAddsMembersToTheirOwnSiteOnly() {
        TestSite own = newSite();
        TestSite other = newSite();
        member(own.id(), "bob", "administrator");
        long erin = userId("erin");
        assertThat(superAdmin("bob")).isFalse();
        assertThat(addMember("bob", own.host(), own.id(), erin, "author")).isEqualTo(200);
        assertThat(roles("erin", own.host())).isEqualTo(List.of("author"));
        // the check is made on the site named in the request, not on the site the request is served for
        assertThat(addMember("bob", own.host(), other.id(), erin, "author")).isEqualTo(403);
        assertThat(addMember("bob", other.host(), other.id(), erin, "author")).isEqualTo(403);
        assertThat(roles("erin", other.host())).isEmpty();
        assertThat(can("erin", other.host(), "read")).isFalse();
    }

    @Test
    void aGrantedNetworkAdministratorMayCreateSitesUntilRevoked() {
        long dave = userId("dave");
        long erin = userId("erin");
        try {
            assertThat(as("alice", "localhost")
                            .post("/it/super-admins/" + dave)
                            .then()
                            .statusCode(200)
                            .extract()
                            .as(Boolean.class))
                    .isTrue();
            assertThat(createSite("dave", uniqueHost("d"))).isEqualTo(200);
            assertThat(as("bob", "localhost").post("/it/super-admins/" + erin).statusCode())
                    .isEqualTo(403);
            assertThat(superAdmin("erin")).isFalse();
        } finally {
            as("alice", "localhost").delete("/it/super-admins/" + dave).then().statusCode(200);
        }
        assertThat(superAdmin("dave")).isFalse();
        String host = uniqueHost("e");
        assertThat(createSite("dave", host)).isEqualTo(403);
        assertThat(createSite("alice", host)).isEqualTo(200);
    }
}
