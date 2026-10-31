package org.quarkpress.restwp.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.quarkpress.restwp.Endpoint;
import org.quarkpress.restwp.Permission;
import org.quarkpress.restwp.PermissionResult;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestResponse;

/** WordPress's Allow rules: later endpoints overwrite earlier ones per method; refusals and failures are left out. */
class AllowHeaderTest {

    static final Permission DENY = request -> PermissionResult.denied(RestError.of("rest_forbidden", "No.", 401));
    static final Permission FAIL = request -> {
        throw new IllegalStateException("boom");
    };

    @Test
    void aLaterEndpointOverwritesAnEarlierOneForTheSameMethodKeepingItsPlace() {
        // GET allowed, then refused by a later endpoint: not listed
        assertThat(allow(endpoint(Permission.PUBLIC, "GET", "POST"), endpoint(DENY, "GET")))
                .contains("POST");
        // GET refused, then allowed later: listed in its first place
        assertThat(allow(
                        endpoint(DENY, "GET"), endpoint(Permission.PUBLIC, "POST"), endpoint(Permission.PUBLIC, "GET")))
                .contains("GET, POST");
    }

    @Test
    void aFailingPermissionCountsAsARefusalAndNothingAllowedIsNoHeader() {
        assertThat(allow(endpoint(FAIL, "GET"), endpoint(Permission.PUBLIC, "DELETE")))
                .contains("DELETE");
        assertThat(allow(endpoint(DENY, "GET"), endpoint(FAIL, "POST"))).isEmpty();
    }

    private static Optional<String> allow(Endpoint... endpoints) {
        RouteTable table = new RouteTable();
        var route = table.registry().route("app/v1", "/things");
        for (Endpoint endpoint : endpoints) {
            route.endpoint(endpoint);
        }
        route.register();
        return AllowHeader.compute(
                new RouteTable.MatchedRoute(table.routes().get(0), Map.of()),
                new StubRequest(RestJson.NODES.objectNode(), "http://localhost/wp-json/app/v1/things", Set.of()));
    }

    private static Endpoint endpoint(Permission permission, String... methods) {
        return Endpoint.of(methods)
                .permission(permission)
                .handler(request -> RestResponse.ok(RestJson.NODES.objectNode()))
                .build();
    }
}
