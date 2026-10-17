package org.quarkpress.core.deployment.gate;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteFeature;
import org.quarkpress.core.deployment.features.TestFeatures;
import org.quarkpress.core.deployment.spi.SiteFeatureBuildItem;

class CatchAllMapperResourceTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(
                    TestFeatures.class,
                    MutableSettings.class,
                    SiteOne.class,
                    Gated.class,
                    CatchAll.class,
                    CatchAllMapperResourceTest.class))
            .overrideConfigKey("quarkus.http.test-port", "0")
            .overrideConfigKey("quarkus.http.test-ssl-port", "0")
            .addBuildChainCustomizer(
                    chain -> TestFeatures.declare(chain, new SiteFeatureBuildItem("demo", "test", false, "Demo")));

    @Path("/gated")
    @SiteFeature("demo")
    public static class Gated {
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String get() {
            return "open";
        }
    }

    @Provider
    public static class CatchAll implements ExceptionMapper<Throwable> {
        @Override
        public Response toResponse(Throwable exception) {
            return Response.status(500).entity("caught").build();
        }
    }

    @Inject
    MutableSettings settings;

    @BeforeEach
    void reset() {
        settings.clear();
    }

    @Test
    void aCatchAllMapperDoesNotTurnTheDisabledResourceInto500() {
        given().when().get("/gated").then().statusCode(404).body(emptyString());
        settings.set("demo", true);
        given().when().get("/gated").then().statusCode(200).body(equalTo("open"));
    }
}
