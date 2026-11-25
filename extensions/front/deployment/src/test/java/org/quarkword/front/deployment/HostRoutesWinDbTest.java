package org.quarkword.front.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The catch-all route runs only when nothing else answered: the host application's resources and static files win, and
 * a REST path is the front end's reserved 404 when the REST module is absent.
 */
class HostRoutesWinDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = FrontApp.with(
            jar -> jar.addAsResource(new StringAsset("static robots\n"), "META-INF/resources/robots-static.txt"),
            HostRoutesWinDbTest.class,
            Invoices.class);

    /** A host application's resource. */
    @Path("/host/invoices")
    public static class Invoices {

        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String list() {
            return "invoices";
        }
    }

    @Inject
    @DataSource("quarkword")
    AgroalDataSource dataSource;

    /** The main site with WordPress's default structure (its options from the migrations have none: plain mode). */
    @BeforeEach
    void structure() {
        TestData.textOption(dataSource, TestData.MAIN, "permalink_structure", "/%year%/%monthnum%/%day%/%postname%/");
    }

    @Test
    void theHostsResourceIsServed() {
        Response answer = Http.as(null).get("/host/invoices");
        answer.then().statusCode(200).body(equalTo("invoices")).header("Expires", nullValue());
        assertThat(answer.headers().getValues("Link")).isEmpty();
        // a path below it that the resource does not match is the front end's
        Http.as(null)
                .get("/host/invoices/2025/")
                .then()
                .statusCode(404)
                .body(containsString("<h1 class=\"entry-title\">Page not found</h1>"));
    }

    @Test
    void aStaticFileIsServed() {
        Response answer = Http.as(null).get("/robots-static.txt");
        answer.then().statusCode(200).body(equalTo("static robots\n"));
        assertThat(answer.headers().getValues("Link")).isEmpty();
    }

    @Test
    void aRestPathWithoutTheRestModuleIsTheReserved404() {
        Http.as(null)
                .get("/wp-json/wp/v2/posts")
                .then()
                .statusCode(404)
                .contentType("text/html; charset=UTF-8")
                .header("Expires", "Wed, 11 Jan 1984 05:00:00 GMT")
                .body(containsString("<body class=\"error404\">"));
    }
}
