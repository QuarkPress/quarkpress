package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.restwp.Endpoint;
import org.quarkpress.restwp.RestResponse;
import org.quarkpress.restwp.RestRouteProvider;
import org.quarkpress.restwp.RouteRegistry;

/**
 * A route pattern with two groups in one path segment. The REST server builds its routes while the application starts,
 * so the refusal fails the start, with a message naming the provider and the refused registration, instead of failing
 * requests later.
 */
class TwoGroupsInSegmentDbTest {

    static final String PATTERN = "/pair/(?P<a>[\\d]+)-(?P<b>[\\d]+)";

    @ApplicationScoped
    public static class TwoGroupRoutes implements RestRouteProvider {

        @Override
        public void register(RouteRegistry routes) {
            routes.route("qp-test/v1", PATTERN)
                    .endpoint(Endpoint.of("GET")
                            .handler(request -> RestResponse.ok(null))
                            .build())
                    .register();
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(TwoGroupsInSegmentDbTest.class, TwoGroupRoutes.class)
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("quarkpress-rest-wp-v2: the REST routes of " + TwoGroupRoutes.class.getName()
                            + " cannot be registered: unsupported route pattern: " + PATTERN
                            + " (two groups in one path segment could make matching take quadratic time)"));

    @Test
    void theApplicationDoesNotStart() {
        // assertException above checks the failure
    }
}
