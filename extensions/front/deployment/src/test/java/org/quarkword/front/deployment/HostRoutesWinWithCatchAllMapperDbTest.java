package org.quarkword.front.deployment;

import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The limit of the catch-all route: a host application whose JAX-RS exception mapper covers {@code NotFoundException}
 * (here through {@code Throwable}) makes Quarkus REST answer every unmatched path itself, so the front end never sees
 * a request.
 */
class HostRoutesWinWithCatchAllMapperDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = FrontApp.with(HostRoutesWinWithCatchAllMapperDbTest.class, CatchAll.class);

    /** Maps every exception, the not-found one of an unmatched path included. */
    @Provider
    public static class CatchAll implements ExceptionMapper<Throwable> {

        @Override
        public Response toResponse(Throwable exception) {
            return Response.status(599).entity("mapped").build();
        }
    }

    @Test
    void catchAllExceptionMapperDisablesTheFrontEnd() {
        Http.as(null).get("/2025/11/20/hello-world/").then().statusCode(599).body(equalTo("mapped"));
        Http.as(null).get("/").then().statusCode(599);
    }
}
