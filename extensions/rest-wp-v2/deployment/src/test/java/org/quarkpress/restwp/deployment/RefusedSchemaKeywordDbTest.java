package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.restwp.Endpoint;
import org.quarkpress.restwp.RestResponse;
import org.quarkpress.restwp.RestRouteProvider;
import org.quarkpress.restwp.RouteRegistry;

/**
 * A route whose item schema uses the refused keyword {@code pattern} (on a property). The REST server builds its routes
 * while the application starts, so the refusal fails the start, with a message naming the provider and the refused
 * registration, instead of failing requests later.
 */
class RefusedSchemaKeywordDbTest {

    @ApplicationScoped
    public static class PatternSchemaRoutes implements RestRouteProvider {

        @Override
        public void register(RouteRegistry routes) {
            ObjectNode schema = JsonNodeFactory.instance.objectNode();
            schema.putObject("properties")
                    .putObject("slug")
                    .put("type", "string")
                    .put("pattern", "^[a-z]+$");
            routes.route("qp-test/v1", "/patterned")
                    .schema(() -> schema)
                    .endpoint(Endpoint.of("GET")
                            .handler(request -> RestResponse.ok(null))
                            .build())
                    .register();
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(RefusedSchemaKeywordDbTest.class, PatternSchemaRoutes.class)
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("quarkpress-rest-wp-v2: the REST routes of " + PatternSchemaRoutes.class.getName()
                            + " cannot be registered: unsupported schema keyword: pattern"));

    @Test
    void theApplicationDoesNotStart() {
        // assertException above checks the failure
    }
}
