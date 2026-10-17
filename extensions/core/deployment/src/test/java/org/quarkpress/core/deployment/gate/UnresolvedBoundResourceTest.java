package org.quarkpress.core.deployment.gate;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyString;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteFeature;
import org.quarkpress.core.deployment.features.TestFeatures;
import org.quarkpress.core.deployment.spi.SiteFeatureBuildItem;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.core.runtime.SiteBinding;

class UnresolvedBoundResourceTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(
                    TestFeatures.class,
                    MutableSettings.class,
                    SiteOne.class,
                    Gated.class,
                    Binder.class,
                    UnresolvedBoundResourceTest.class))
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
    @PreMatching
    @Priority(1)
    public static class Binder implements ContainerRequestFilter {
        @Inject
        SiteBinding binding;

        @Override
        public void filter(ContainerRequestContext context) {
            binding.bind(Binding.UNRESOLVED);
        }
    }

    @Inject
    MutableSettings settings;

    @BeforeEach
    void reset() {
        settings.clear();
    }

    @Test
    void theResourceAnswersAnEmpty404EvenWhenTheFeatureIsOnElsewhere() {
        settings.set("demo", true);
        given().when().get("/gated").then().statusCode(404).body(emptyString());
    }
}
