package org.quarkpress.security.deployment;

import static io.restassured.RestAssured.given;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.CapabilityDeniedException;

/** An application's own mapper of a network capability refusal, at the default priority, wins over the extension's. */
class CapabilityDeniedOwnMapperDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            SecurityApp.multisite(CapabilityDeniedOwnMapperDbTest.class, DeniedResource.class, OwnMapper.class);

    @Path("/it/denied")
    public static class DeniedResource {

        @GET
        public String denied() {
            throw new CapabilityDeniedException("manage_sites", true);
        }
    }

    @Provider
    public static class OwnMapper implements ExceptionMapper<CapabilityDeniedException> {

        @Override
        public Response toResponse(CapabilityDeniedException exception) {
            return Response.status(418).build();
        }
    }

    @Test
    void theApplicationsMapperAnswers() {
        given().get("/it/denied").then().statusCode(418);
    }
}
