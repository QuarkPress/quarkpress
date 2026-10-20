package org.quarkpress.security.deployment;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.CapabilityCheck;
import org.quarkpress.core.CapabilityDeniedException;

/**
 * With Quarkus REST, a network capability the caller lacks answers as a refused {@code @PermissionsAllowed} does,
 * without a mapper of the application's own: 401 with the bearer challenge for an anonymous caller, 403 otherwise.
 */
class CapabilityDeniedMappingDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            SecurityApp.multisite(CapabilityDeniedMappingDbTest.class, NetworkResource.class);

    @Path("/it/network")
    public static class NetworkResource {

        @Inject
        CapabilityCheck check;

        @GET
        @Path("/{capability}")
        public String require(@PathParam("capability") String capability) {
            check.requireNetwork(capability);
            return "allowed";
        }

        @GET
        @Path("/denied-to-a-user")
        public String deniedToAUser() {
            throw new CapabilityDeniedException("manage_sites");
        }
    }

    @Test
    void anAnonymousCallerIsChallenged() {
        given().get("/it/network/create_sites")
                .then()
                .statusCode(401)
                .header("WWW-Authenticate", is("Bearer"))
                .body(is(emptyOrNullString()));
    }

    @Test
    void anAuthenticatedCallerIsForbidden() {
        given().get("/it/network/denied-to-a-user")
                .then()
                .statusCode(403)
                .header("WWW-Authenticate", nullValue())
                .body(is(emptyOrNullString()));
    }
}
