package org.quarkword.front.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.SiteContext;
import org.quarkword.core.SiteId;
import org.quarkword.front.runtime.FrontSiteGate;

/**
 * On a network, a front-end read on an alias domain set to redirect is sent to the site's own address with its path
 * and query (QuarkWord's alias redirect, as the REST module's), and an archived, spam or deleted site answers 410 with
 * WordPress's message.
 */
class FrontSiteGateDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = FrontApp.multisite(FrontSiteGateDbTest.class, GateProbe.class);

    /** The gate's answer for the request: {@code 301 <location>}, {@code 410 <message>} or {@code go}. */
    @Path("/gate-probe")
    @Produces(MediaType.TEXT_PLAIN)
    public static class GateProbe {

        @Inject
        SiteContext site;

        @Inject
        FrontSiteGate gate;

        @GET
        public String get(@Context UriInfo uri) {
            return answer("GET", uri);
        }

        @POST
        public String post(@Context UriInfo uri) {
            return answer("POST", uri);
        }

        private String answer(String method, UriInfo uri) {
            String target = uri.getRequestUri().getRawPath()
                    + (uri.getRequestUri().getRawQuery() == null
                            ? ""
                            : "?" + uri.getRequestUri().getRawQuery());
            return gate.decide(site, method, target)
                    .map(answer -> switch (answer) {
                        case FrontSiteGate.Moved moved ->
                            moved.redirect().status() + " " + moved.redirect().location();
                        case FrontSiteGate.Gone gone -> gone.status() + " " + gone.message();
                    })
                    .orElse("go");
        }
    }

    @Inject
    @DataSource("quarkword")
    AgroalDataSource dataSource;

    @Test
    void anAliasReadGoesToTheSitesAddress() {
        SiteId site = TestData.site(dataSource);
        String domain = TestData.domain(dataSource, site);
        String alias = "alias-" + UUID.randomUUID().toString().substring(0, 8) + ".test";
        Jdbc.update(
                dataSource,
                "insert into qw_site_domain (site_id, domain, redirect_to_primary) values (?, ?, ?)",
                site.value(),
                alias,
                true);
        assertThat(Http.at(alias, null)
                        .urlEncodingEnabled(false)
                        .get("/gate-probe?x=1&y=a%20b")
                        .then()
                        .extract()
                        .asString())
                .isEqualTo("301 http://" + domain + "/gate-probe?x=1&y=a%20b");
        assertThat(Http.at(alias, null).post("/gate-probe?x=1").then().extract().asString())
                .isEqualTo("go");
        assertThat(Http.at(domain, null).get("/gate-probe?x=1").then().extract().asString())
                .isEqualTo("go");
    }

    @Test
    void aSiteThatIsNoMoreAnswers410() {
        SiteId site = TestData.site(dataSource);
        String domain = TestData.domain(dataSource, site);
        for (String flag : new String[] {"archived", "spam", "deleted"}) {
            Jdbc.update(dataSource, "update qw_site set " + flag + " = ? where id = ?", true, site.value());
            String message = flag.equals("deleted") ? FrontSiteGate.GONE : FrontSiteGate.ARCHIVED;
            assertThat(Http.at(domain, null).get("/gate-probe").then().extract().asString())
                    .isEqualTo("410 " + message);
            assertThat(Http.at(domain, null)
                            .post("/gate-probe")
                            .then()
                            .extract()
                            .asString())
                    .isEqualTo("410 " + message);
            Jdbc.update(dataSource, "update qw_site set " + flag + " = ? where id = ?", false, site.value());
        }
        assertThat(FrontSiteGate.ARCHIVED).isEqualTo("This site has been archived or suspended.");
        assertThat(FrontSiteGate.GONE).isEqualTo("This site is no longer available.");
    }
}
