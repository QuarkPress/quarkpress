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
 * The {@code postpass} action on a network below a path: the main site (the network's address) moved to
 * {@code /net/}, a sub-site at {@code /net/site2/}. The cookie is the network's: named after the main site's
 * {@code siteurl} with a trailing slash ({@code http://wp.test/net/}) and set on the network's path {@code /net/}, as
 * WordPress's {@code COOKIEPATH} on a multisite is the network's path ({@code php postpass-fix.php}).
 */
class PostPassSubdirectoryNetworkDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = FrontApp.multisite(PostPassSubdirectoryNetworkDbTest.class);

    /** {@code md5("http://wp.test/net/")}, from {@code php -r 'echo md5("http://wp.test/net/");'}. */
    static final String NETWORK_NAME = "wp-postpass_3b5a993573788f702344f349b345d617";

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
        home = "http://" + host + "/net/site2";
        Jdbc.update(dataSource, "update qw_site set path = '/net/' where id = ?", TestData.MAIN.value());
        TestData.textOption(dataSource, TestData.MAIN, "siteurl", "http://wp.test/net");
        Jdbc.update(
                dataSource, "insert into qw_site (domain, path, name) values ('localhost', '/net/site2/', 'site2')");
        SiteId site2 = SiteId.of(
                Jdbc.count(dataSource, "select id from qw_site where domain = 'localhost' and path = '/net/site2/'"));
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
    void theCookieLivesOnTheNetworksPath() {
        String permalink = home + "/?p=" + locked.value();
        Response response = Http.at(host, null)
                .contentType("application/x-www-form-urlencoded")
                .body("post_password=s3&redirect_to=" + URLEncoder.encode(permalink, StandardCharsets.UTF_8))
                .post("/net/site2/wp-login.php?action=postpass");
        response.then().statusCode(302).header("Location", permalink);
        List<String> cookies = response.headers().getValues("Set-Cookie");
        assertThat(cookies).hasSize(1);
        assertThat(cookies.get(0)).startsWith(NETWORK_NAME + "=%24P%24B").endsWith("; Max-Age=864000; path=/net/");
        String pair = cookies.get(0).substring(0, cookies.get(0).indexOf(';'));
        Http.at(host, null)
                .header("Cookie", pair)
                .get("/net/site2/?p=" + locked.value())
                .then()
                .statusCode(200)
                .body(containsString("SECRET CONTENT"), not(containsString("post-password-form")));
    }
}
