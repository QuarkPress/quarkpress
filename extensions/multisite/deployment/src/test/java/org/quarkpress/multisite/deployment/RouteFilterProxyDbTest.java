package org.quarkpress.multisite.deployment;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;

class RouteFilterProxyDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MultisiteApp.with(
                    RouteFilterProxyDbTest.class, Network.class, Probe.class, RawHttp.class)
            .overrideConfigKey("quarkus.http.proxy.proxy-address-forwarding", "true")
            .overrideConfigKey("quarkus.http.proxy.allow-x-forwarded", "true")
            .overrideConfigKey("quarkus.http.proxy.enable-forwarded-host", "true");

    @Path("/")
    @Produces(MediaType.APPLICATION_JSON)
    public static class Probe {
        @Inject
        SiteContext context;

        @GET
        @Path("{path: .*}")
        public Map<String, Object> probe() {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("site", context.isResolved() ? context.siteId().value() : null);
            return view;
        }
    }

    @Inject
    Network network;

    @Test
    void aForwardedHostIsUsedWhenTheProxyIsConfigured() {
        String domain = Network.unique("fwd") + ".example";
        SiteId site = network.site(domain, "/");
        given().header("Host", "localhost")
                .header("X-Forwarded-Host", domain)
                .get("/x")
                .then()
                .statusCode(200)
                .body("site", is((int) site.value()));
    }

    @Test
    void theHostIsUsedWithoutAForwardedHost() {
        String domain = Network.unique("plain") + ".example";
        SiteId site = network.site(domain, "/");
        given().header("Host", domain).get("/x").then().statusCode(200).body("site", is((int) site.value()));
    }

    @Test
    void anAbsoluteTargetThatContradictsTheHostIsRefusedWithForwardingToo() throws Exception {
        String domain = Network.unique("abs") + ".example";
        network.site(domain, "/");
        assertThat(RawHttp.request("GET http://" + domain + "/x", "Host: localhost", "X-Forwarded-Host: " + domain)
                        .status())
                .isEqualTo(400);
    }
}
