package org.quarkpress.multisite.deployment;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.test.QuarkusExtensionTest;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.restassured.http.ContentType;
import io.smallrye.common.annotation.NonBlocking;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteRequest;
import org.quarkpress.core.SiteResolver;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.core.runtime.SiteBinding;
import org.quarkpress.multisite.runtime.HttpPresetSite;
import org.quarkpress.persistence.runtime.Scopes;

class RouteFilterDbTest {

    static final AtomicInteger CALLS = new AtomicInteger();

    static final ObjectMapper JSON = new ObjectMapper();

    @RegisterExtension
    static final QuarkusExtensionTest app = MultisiteApp.with(
            RouteFilterDbTest.class,
            Network.class,
            Probe.class,
            Counting.class,
            Early.class,
            Slow.class,
            Rerouter.class,
            RawHttp.class);

    /** Asked before the built-in resolvers; counts real requests and fails for one host. */
    @ApplicationScoped
    @Priority(1000)
    public static class Counting implements SiteResolver {
        @Override
        public Optional<SiteId> resolve(SiteRequest request) {
            if (!request.isNone()) {
                CALLS.incrementAndGet();
            }
            if (request.host().equals("boom.example")) {
                throw new IllegalStateException("resolver failed");
            }
            return Optional.empty();
        }
    }

    @Path("/")
    @Produces(MediaType.APPLICATION_JSON)
    public static class Probe {
        @Inject
        SiteContext context;

        @Inject
        SiteBinding binding;

        private Map<String, Object> view() {
            Map<String, Object> view = new LinkedHashMap<>();
            // read before anything resolves lazily: present only when the filter decided the request's site
            view.put("decided", binding.bound().isPresent());
            view.put("resolved", context.isResolved());
            view.put("site", context.isResolved() ? context.siteId().value() : null);
            view.put("redirect", context.redirect().map(URI::toString).orElse(null));
            return view;
        }

        @GET
        @Path("{path: .*}")
        public Map<String, Object> probe() {
            return view();
        }

        @PUT
        @Path("echo/{path: .*}")
        @Consumes(MediaType.APPLICATION_JSON)
        public Map<String, Object> echo(JsonNode body) {
            Map<String, Object> view = view();
            view.put("body", body);
            return view;
        }

        @Inject
        HttpPresetSite preset;

        /** What a scope's fresh virtual thread sees of the routed site: nothing. */
        @GET
        @Path("scopethread/{path: .*}")
        public Map<String, Object> scopeThread() throws Exception {
            boolean[] seen = new boolean[2];
            Thread thread = Thread.ofVirtual().start(() -> {
                seen[0] = preset.binding().isPresent();
                seen[1] = preset.redirect().isPresent();
            });
            thread.join();
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("binding", seen[0]);
            view.put("redirect", seen[1]);
            view.put("here", preset.binding().isPresent());
            return view;
        }

        /** Work for another site sees that site; the request still sees its own afterwards. */
        @GET
        @Path("crosssite/{other}/{path: .*}")
        public Map<String, Object> crossSite(@PathParam("other") long other) throws Exception {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("before", context.siteId().value());
            view.put(
                    "inside",
                    Scopes.within(
                            Binding.site(SiteId.of(other)),
                            () -> context.siteId().value()));
            view.put("after", context.siteId().value());
            return view;
        }

        @GET
        @Path("fast/{path: .*}")
        @NonBlocking
        public Map<String, Object> fast() {
            Binding current = binding.current();
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("resolved", current.kind() == Binding.Kind.SITE);
            view.put(
                    "site", current.kind() == Binding.Kind.SITE ? current.site().value() : null);
            return view;
        }
    }

    /** A Vert.x filter that runs after the site filter and before Quarkus REST, as authentication does. */
    @ApplicationScoped
    public static class Early {
        @Inject
        SiteBinding binding;

        void register(@Observes Filters filters) {
            filters.register(this::read, 150);
        }

        void read(RoutingContext context) {
            if (!context.normalizedPath().startsWith("/early/")) {
                context.next();
                return;
            }
            ManagedContext requestContext = Arc.container().requestContext();
            boolean activated = !requestContext.isActive();
            if (activated) {
                requestContext.activate();
            }
            try {
                Binding current = binding.current();
                context.response()
                        .putHeader(
                                "X-Early-Site",
                                current.kind() == Binding.Kind.SITE
                                        ? Long.toString(current.site().value())
                                        : current.kind().name());
            } finally {
                if (activated) {
                    requestContext.terminate();
                }
            }
            context.next();
        }
    }

    /** A filter between the site filter and Quarkus REST that defers next(), as an asynchronous authentication does. */
    @ApplicationScoped
    public static class Slow {
        void register(@Observes Filters filters) {
            filters.register(
                    context -> {
                        if (context.normalizedPath().startsWith("/echo/slow/")) {
                            context.vertx().setTimer(100, timer -> context.next());
                        } else {
                            context.next();
                        }
                    },
                    200);
        }
    }

    /** Re-routes a request, which runs the filters again. */
    @ApplicationScoped
    public static class Rerouter {
        void register(@Observes Filters filters) {
            filters.register(
                    context -> {
                        if (context.normalizedPath().startsWith("/reroute/")) {
                            context.reroute("/x");
                        } else {
                            context.next();
                        }
                    },
                    150);
        }
    }

    @Inject
    Network network;

    @Test
    void aMappedDomainSelectsTheSite() {
        String domain = Network.unique("d") + ".example";
        SiteId site = network.site(domain, "/");
        given().header("Host", domain)
                .get("/x")
                .then()
                .statusCode(200)
                .body("resolved", is(true))
                .body("site", is((int) site.value()));
    }

    @Test
    void hostsWithPortsAndCaseResolve() {
        String domain = Network.unique("c") + ".example";
        SiteId site = network.site(domain, "/");
        given().header("Host", domain.toUpperCase(java.util.Locale.ROOT) + ":8443")
                .get("/")
                .then()
                .statusCode(200)
                .body("site", is((int) site.value()));
    }

    @Test
    void aPathPrefixSelectsTheSite() {
        String slug = Network.unique("p");
        SiteId site = network.site("localhost", "/" + slug + "/");
        given().header("Host", "localhost")
                .get("/" + slug + "/page")
                .then()
                .statusCode(200)
                .body("site", is((int) site.value()));
        given().header("Host", "localhost").get("/other").then().statusCode(200).body("site", is(1));
    }

    @Test
    void aRequestWithoutAKnownHostIsServedUnresolved() {
        given().header("Host", Network.unique("nobody") + ".example")
                .get("/x")
                .then()
                .statusCode(200)
                .body("resolved", is(false))
                .body("site", nullValue());
    }

    @Test
    void aBodyArrivesAfterResolution() {
        String domain = Network.unique("b") + ".example";
        SiteId site = network.site(domain, "/");
        given().header("Host", domain)
                .contentType(ContentType.JSON)
                .body("{\"k\":[1,2]}")
                .put("/echo/x")
                .then()
                .statusCode(200)
                .body("site", is((int) site.value()))
                .body("body.k", contains(1, 2));
    }

    @Test
    void aRedirectingAliasExposesThePrimaryUrl() {
        String domain = Network.unique("r") + ".example";
        SiteId site = network.site(domain, "/");
        String alias = Network.unique("ra") + ".example";
        network.alias(site, alias, true);
        given().header("Host", alias)
                .get("/x")
                .then()
                .statusCode(200)
                .body("site", is((int) site.value()))
                .body("redirect", is("http://" + domain + "/"));
        given().header("Host", domain).get("/x").then().statusCode(200).body("redirect", nullValue());
    }

    @Test
    void aFailingResolverAnswers500() {
        given().header("Host", "boom.example").get("/x").then().statusCode(500);
        String domain = Network.unique("after") + ".example";
        SiteId site = network.site(domain, "/");
        given().header("Host", domain).get("/x").then().statusCode(200).body("site", is((int) site.value()));
    }

    @Test
    void theNonApplicationRootIsNotResolved() {
        int before = CALLS.get();
        given().header("Host", "localhost").get("/control").then().statusCode(200);
        int afterControl = CALLS.get();
        assertThat(afterControl).isEqualTo(before + 1);
        given().header("Host", "localhost").get("/q/anything").then().statusCode(200);
        assertThat(CALLS.get()).isEqualTo(afterControl);
    }

    @Test
    void aRouteRunningBeforeRestSeesTheSite() {
        String domain = Network.unique("early") + ".example";
        SiteId site = network.site(domain, "/");
        given().header("Host", domain)
                .get("/early/x")
                .then()
                .statusCode(200)
                .header("X-Early-Site", String.valueOf(site.value()))
                .body("site", is((int) site.value()));
        given().header("Host", Network.unique("nobody") + ".example")
                .get("/early/x")
                .then()
                .statusCode(200)
                .header("X-Early-Site", "UNRESOLVED");
    }

    @Test
    void dotSegmentsAreNotLookedUp() throws Exception {
        String slug = Network.unique("dots");
        network.site("localhost", "/" + slug + "/");
        int before = CALLS.get();
        // the router serves /x; the filter decides (the normalized path is not under /q/) without a lookup
        RawHttp.Response skipped = RawHttp.get("localhost", "/q/../x");
        assertThat(skipped.status()).isEqualTo(200);
        JsonNode outside = JSON.readTree(skipped.body());
        assertThat(outside.get("decided").booleanValue()).isTrue();
        assertThat(outside.get("resolved").booleanValue()).isFalse();
        // the router serves /x, not the site at /<slug>/: no site
        JsonNode escaped =
                JSON.readTree(RawHttp.get("localhost", "/" + slug + "/../x").body());
        assertThat(escaped.get("decided").booleanValue()).isTrue();
        assertThat(escaped.get("resolved").booleanValue()).isFalse();
        assertThat(CALLS.get()).isEqualTo(before);
        given().header("Host", "localhost").get("/control").then().statusCode(200);
        int afterControl = CALLS.get();
        assertThat(afterControl).isEqualTo(before + 1);
        // %71 is "q": the normalized path lies under the non-application root, so nothing is resolved
        assertThat(RawHttp.get("localhost", "/%71/anything").status()).isEqualTo(200);
        assertThat(CALLS.get()).isEqualTo(afterControl);
    }

    @Test
    void resolutionRunsOncePerRequest() {
        int before = CALLS.get();
        given().header("Host", "localhost").get("/once").then().statusCode(200);
        assertThat(CALLS.get() - before).isEqualTo(1);
    }

    @Test
    void aNonBlockingEndpointSeesTheBinding() {
        String domain = Network.unique("nb") + ".example";
        SiteId site = network.site(domain, "/");
        given().header("Host", domain).get("/fast/x").then().statusCode(200).body("site", is((int) site.value()));
    }

    @Test
    void aForwardedHostIsIgnoredWithoutProxyConfiguration() {
        String domain = Network.unique("fwd") + ".example";
        network.site(domain, "/");
        given().header("Host", "localhost")
                .header("X-Forwarded-Host", domain)
                .get("/x")
                .then()
                .statusCode(200)
                .body("site", is(1));
    }

    @Test
    void theQueryDoesNotHideTheSite() throws Exception {
        String slug = Network.unique("q");
        SiteId site = network.site("localhost", "/" + slug + "/");
        JsonNode view = JSON.readTree(
                RawHttp.get("localhost", "/" + slug + "/page?a=/b/../c").body());
        assertThat(view.get("site").longValue()).isEqualTo(site.value());
    }

    @Test
    void aScopeThreadDoesNotSeeTheRoutedSite() {
        String domain = Network.unique("scope") + ".example";
        network.site(domain, "/");
        given().header("Host", domain)
                .get("/scopethread/x")
                .then()
                .statusCode(200)
                .body("binding", is(false))
                .body("redirect", is(false))
                .body("here", is(true));
    }

    @Test
    void nothingIsPresetOutsideARequest() {
        assertThat(presetOutside.binding()).isEmpty();
        assertThat(presetOutside.redirect()).isEmpty();
    }

    @Inject
    HttpPresetSite presetOutside;

    @Test
    void aBodySurvivesAnAsynchronousFilterBeforeRest() {
        String domain = Network.unique("slow") + ".example";
        SiteId site = network.site(domain, "/");
        given().header("Host", domain)
                .contentType(ContentType.JSON)
                .body("{\"k\":[1,2,3]}")
                .put("/echo/slow/x")
                .then()
                .statusCode(200)
                .body("site", is((int) site.value()))
                .body("body.k", contains(1, 2, 3));
    }

    @Test
    void anAbsoluteTargetThatMatchesTheHostResolves() throws Exception {
        String domain = Network.unique("abs") + ".example";
        SiteId site = network.site(domain, "/");
        RawHttp.Response response = RawHttp.request(
                "GET http://" + domain.toUpperCase(java.util.Locale.ROOT) + ":81/x", "Host: " + domain + ":82");
        assertThat(response.status()).isEqualTo(200);
        assertThat(JSON.readTree(response.body()).get("site").longValue()).isEqualTo(site.value());
    }

    @Test
    void anAbsoluteTargetThatContradictsTheHostIsRefused() throws Exception {
        String domain = Network.unique("abs") + ".example";
        network.site(domain, "/");
        int before = CALLS.get();
        assertThat(RawHttp.request("GET http://" + domain + "/x", "Host: localhost")
                        .status())
                .isEqualTo(400);
        assertThat(RawHttp.request("GET http://" + domain + "/x", "Host: " + domain + ".evil.example")
                        .status())
                .isEqualTo(400);
        assertThat(CALLS.get()).isEqualTo(before);
    }

    @Test
    void targetsThatAreNotPathsResolveToNoSite() throws Exception {
        int before = CALLS.get();
        for (String line : new String[] {"OPTIONS *", "GET localhost:443", "GET http:/x", "GET ftp"}) {
            RawHttp.Response response = RawHttp.request(line, "Host: localhost");
            assertThat(response.status()).as(line).isLessThan(500);
        }
        assertThat(CALLS.get()).isEqualTo(before);
    }

    @Test
    void moreThanOneHostHeaderIsRefused() throws Exception {
        assertThat(RawHttp.request("GET /x", "Host: localhost", "Host: other.example")
                        .status())
                .isEqualTo(400);
    }

    @Test
    void aReroutedRequestKeepsItsDecision() {
        String domain = Network.unique("rr") + ".example";
        SiteId site = network.site(domain, "/");
        int afterCreate = CALLS.get();
        given().header("Host", domain).get("/reroute/a").then().statusCode(200).body("site", is((int) site.value()));
        assertThat(CALLS.get() - afterCreate).isEqualTo(1);
    }

    @Test
    void workForAnotherSiteSeesThatSiteAndTheRequestKeepsItsOwn() {
        String a = Network.unique("ca") + ".example";
        SiteId siteA = network.site(a, "/");
        SiteId siteB = network.site(Network.unique("cb") + ".example", "/");
        given().header("Host", a)
                .get("/crosssite/" + siteB.value() + "/x")
                .then()
                .statusCode(200)
                .body("before", is((int) siteA.value()))
                .body("inside", is((int) siteB.value()))
                .body("after", is((int) siteA.value()));
    }
}
