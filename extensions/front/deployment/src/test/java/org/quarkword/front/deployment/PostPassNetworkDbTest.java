package org.quarkword.front.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.PostId;
import org.quarkword.core.SiteId;
import org.quarkword.persistence.runtime.SiteDefaults;

/**
 * The {@code postpass} action on a multisite: a sub-site under {@code /site2/} of the network's domain sets the
 * network's cookie, named after the main site's {@code siteurl} with a trailing slash (here {@code http://wp.test/},
 * whatever the sub-site's own address) on the network's path {@code /}, as WordPress's network options make it.
 */
class PostPassNetworkDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = FrontApp.multisite(PostPassNetworkDbTest.class);

    /** {@code md5("http://wp.test/")}, from {@code php -r 'echo md5("http://wp.test/");'}. */
    static final String NETWORK_NAME = "wp-postpass_f247b58bb6fdc61f0d164edf55afad99";

    @Inject
    @DataSource("quarkword")
    AgroalDataSource dataSource;

    static String host;
    static String home;
    static PostId locked;

    @BeforeEach
    void cast() {
        if (locked != null) {
            return;
        }
        host = "localhost:" + RestAssured.port;
        home = "http://" + host + "/site2";
        TestData.textOption(dataSource, TestData.MAIN, "siteurl", "http://wp.test");
        Jdbc.update(dataSource, "insert into qw_site (domain, path, name) values ('localhost', '/site2/', 'site2')");
        SiteId site2 = SiteId.of(
                Jdbc.count(dataSource, "select id from qw_site where domain = 'localhost' and path = '/site2/'"));
        for (Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> option :
                SiteDefaults.forNewSite("site2", home).entrySet()) {
            TestData.siteOption(
                    dataSource, site2, option.getKey(), option.getValue().toString());
        }
        TestData.textOption(dataSource, site2, "home", home);
        TestData.textOption(dataSource, site2, "siteurl", home);
        TestData.textOption(dataSource, site2, "permalink_structure", "");
        locked = TestData.post(
                dataSource, site2, "post", "publish", "locked", null, null, "2026-06-01T10:00:00Z", false);
        Jdbc.update(
                dataSource,
                "update qw_post set title = 'Locked', content = 'SECRET CONTENT', password = 's3' where id = ?",
                locked.value());
    }

    @Test
    void aSubSitesCookieIsTheNetworks() {
        String permalink = home + "/?p=" + locked.value();
        Response response = Http.at(host, null)
                .contentType("application/x-www-form-urlencoded")
                .body("post_password=s3&redirect_to=" + URLEncoder.encode(permalink, StandardCharsets.UTF_8))
                .post("/site2/wp-login.php?action=postpass");
        response.then().statusCode(302).header("Location", permalink);
        List<String> cookies = response.headers().getValues("Set-Cookie");
        assertThat(cookies).hasSize(1);
        assertThat(cookies.get(0)).startsWith(NETWORK_NAME + "=%24P%24B").endsWith("; Max-Age=864000; path=/");
        String pair = cookies.get(0).substring(0, cookies.get(0).indexOf(';'));
        Http.at(host, null)
                .header("Cookie", pair)
                .get("/site2/?p=" + locked.value())
                .then()
                .statusCode(200)
                .body(containsString("SECRET CONTENT"), not(containsString("post-password-form")));
        Http.at(host, null)
                .get("/site2/?p=" + locked.value())
                .then()
                .statusCode(200)
                .body(
                        containsString("<form action=\"" + home + "/wp-login.php?action=postpass\""),
                        not(containsString("SECRET CONTENT")));
    }
}
