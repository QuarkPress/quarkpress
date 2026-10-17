package org.quarkpress.core.deployment.gate;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteFeature;
import org.quarkpress.core.deployment.features.TestFeatures;
import org.quarkpress.core.deployment.spi.SiteFeatureBuildItem;

class GatedResourceTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(
                    TestFeatures.class,
                    MutableSettings.class,
                    SiteOne.class,
                    Gated.class,
                    ParentEndpoints.class,
                    Inheriting.class,
                    Mixed.class,
                    Posting.class,
                    Locator.class,
                    Sub.class,
                    GatedResourceTest.class))
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

    public static class ParentEndpoints {
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String inherited() {
            return "parent";
        }
    }

    @Path("/inheriting")
    @SiteFeature("demo")
    public static class Inheriting extends ParentEndpoints {}

    @Path("/mixed")
    public static class Mixed {
        @GET
        @Path("/gated")
        @Produces(MediaType.TEXT_PLAIN)
        @SiteFeature("demo")
        public String gated() {
            return "gated";
        }

        @GET
        @Path("/free")
        @Produces(MediaType.TEXT_PLAIN)
        public String free() {
            return "free";
        }
    }

    @Path("/posting")
    public static class Posting {
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @SiteFeature("demo")
        public String post(Map<String, String> body) {
            return "posted " + body.get("name");
        }
    }

    @Path("/locator")
    public static class Locator {
        @Path("/sub")
        @SiteFeature("demo")
        public Sub sub() {
            return new Sub();
        }
    }

    public static class Sub {
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String get() {
            return "sub";
        }
    }

    @Inject
    MutableSettings settings;

    @BeforeEach
    void reset() {
        settings.clear();
    }

    @Test
    void aDisabledResourceAnswers404() {
        given().when().get("/gated").then().statusCode(404).body(emptyString());
    }

    @Test
    void anEnabledResourceAnswers() {
        settings.set("demo", true);
        given().when().get("/gated").then().statusCode(200).body(equalTo("open"));
    }

    @Test
    void aClassLevelGateCoversInheritedResourceMethods() {
        given().when().get("/inheriting").then().statusCode(404).body(emptyString());
        settings.set("demo", true);
        given().when().get("/inheriting").then().statusCode(200).body(equalTo("parent"));
    }

    @Test
    void aMethodLevelGateLeavesTheOtherEndpointsOpen() {
        given().when().get("/mixed/gated").then().statusCode(404).body(emptyString());
        given().when().get("/mixed/free").then().statusCode(200).body(equalTo("free"));
        settings.set("demo", true);
        given().when().get("/mixed/gated").then().statusCode(200).body(equalTo("gated"));
    }

    @Test
    void aDisabledPostWithAJsonBodyAnswersAnEmpty404() {
        given().contentType("application/json")
                .body("{\"name\":\"x\"}")
                .when()
                .post("/posting")
                .then()
                .statusCode(404)
                .body(emptyString());
        settings.set("demo", true);
        given().contentType("application/json")
                .body("{\"name\":\"x\"}")
                .when()
                .post("/posting")
                .then()
                .statusCode(200)
                .body(equalTo("posted x"));
    }

    @Test
    void aDisabledSubResourceLocatorAnswersAnEmpty404() {
        given().when().get("/locator/sub").then().statusCode(404).body(emptyString());
        settings.set("demo", true);
        given().when().get("/locator/sub").then().statusCode(200).body(equalTo("sub"));
    }
}
