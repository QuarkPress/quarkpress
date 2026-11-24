package org.quarkword.front.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The URLs of a single site whose {@code home} and {@code siteurl} options differ, over plain http and over https
 * (the proxy header), with the schemes WordPress's {@code home_url}, {@code site_url}, {@code admin_url},
 * {@code wp_login_url} and {@code get_rest_url} give them.
 */
class FrontUrlsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = FrontApp.with(FrontUrlsDbTest.class, ProbeResource.class);

    @Inject
    @DataSource("quarkword")
    AgroalDataSource dataSource;

    @BeforeEach
    void split() {
        TestData.textOption(dataSource, TestData.MAIN, "home", "http://front.test");
        TestData.textOption(dataSource, TestData.MAIN, "siteurl", "http://front.test/wp");
        TestData.textOption(dataSource, TestData.MAIN, "permalink_structure", "/%year%/%monthnum%/%day%/%postname%/");
    }

    private static RequestSpecification http() {
        return Http.at("front.test", null);
    }

    private static RequestSpecification https() {
        return http().header("X-Forwarded-Proto", "https");
    }

    private static String probe(RequestSpecification request, String path) {
        return request.get("/probe" + path).then().statusCode(200).extract().asString();
    }

    @Test
    void overHttpTheStoredSchemesAreKept() {
        assertThat(probe(http(), ""))
                .isEqualTo("http://front.test|http://front.test/|http://front.test/?p=1|http://front.test/wp"
                        + "|http://front.test/wp/wp-admin/|http://front.test/wp/wp-login.php"
                        + "|http://front.test/wp-json/wp/v2/posts|");
        assertThat(probe(http(), "/more"))
                .isEqualTo("http://front.test/wp-json/|http://front.test/wp-json/|false|false|http://front.test/wp/"
                        + "|http://front.test/wp/wp-admin/post.php|http://front.test|http://front.test/x");
    }

    @Test
    void overHttpsEveryUrlIsHttps() {
        assertThat(probe(https(), ""))
                .isEqualTo("https://front.test|https://front.test/|https://front.test/?p=1|https://front.test/wp"
                        + "|https://front.test/wp/wp-admin/|https://front.test/wp/wp-login.php"
                        + "|https://front.test/wp-json/wp/v2/posts|");
        assertThat(probe(https(), "/more"))
                .startsWith("https://front.test/wp-json/|https://front.test/wp-json/|false|true|");
    }

    @Test
    void anHttpsHomeKeepsItsSchemeOverHttpButSiteurlFollowsTheRequest() {
        TestData.textOption(dataSource, TestData.MAIN, "home", "https://front.test");
        assertThat(probe(http(), ""))
                .isEqualTo("https://front.test|https://front.test/|https://front.test/?p=1|http://front.test/wp"
                        + "|http://front.test/wp/wp-admin/|http://front.test/wp/wp-login.php"
                        + "|https://front.test/wp-json/wp/v2/posts|");
        assertThat(probe(http(), "/more"))
                .startsWith("https://front.test/wp-json/|https://front.test/wp-json/|false|false|");
    }

    @Test
    void anHttpsSiteurlForcesSslOnTheAdminOnly() {
        TestData.textOption(dataSource, TestData.MAIN, "siteurl", "https://front.test/wp");
        assertThat(probe(http(), ""))
                .isEqualTo("http://front.test|http://front.test/|http://front.test/?p=1|http://front.test/wp"
                        + "|https://front.test/wp/wp-admin/|https://front.test/wp/wp-login.php"
                        + "|http://front.test/wp-json/wp/v2/posts|");
        assertThat(probe(http(), "/more"))
                .isEqualTo("http://front.test/wp-json/|http://front.test/wp-json/|true|false|http://front.test/wp/"
                        + "|https://front.test/wp/wp-admin/post.php|http://front.test|http://front.test/x");
    }

    @Test
    void plainModeRestUrlsGoThroughIndexPhp() {
        TestData.textOption(dataSource, TestData.MAIN, "permalink_structure", "");
        assertThat(probe(http(), "")).endsWith("|http://front.test/index.php?rest_route=/wp/v2/posts|");
        assertThat(probe(http(), "/more"))
                .startsWith("http://front.test/index.php?rest_route=/|http://front.test/index.php?rest_route=/|");
    }

    @Test
    void pathinfoStructuresPrefixTheRestUrlWithIndexPhp() {
        TestData.textOption(dataSource, TestData.MAIN, "permalink_structure", "/index.php/%postname%/");
        assertThat(probe(http(), "")).endsWith("|http://front.test/index.php/wp-json/wp/v2/posts|");
        assertThat(probe(http(), "/more"))
                .startsWith("http://front.test/index.php/wp-json/|http://front.test/index.php/wp-json/|");
    }

    @Test
    void aHomeWithAPathHasThatPath() {
        TestData.textOption(dataSource, TestData.MAIN, "home", "http://front.test/site2");
        assertThat(probe(http(), ""))
                .isEqualTo("http://front.test/site2|http://front.test/site2/|http://front.test/site2/?p=1"
                        + "|http://front.test/wp|http://front.test/wp/wp-admin/|http://front.test/wp/wp-login.php"
                        + "|http://front.test/site2/wp-json/wp/v2/posts|site2");
    }

    @Test
    void aStoredTrailingSlashIsRemoved() {
        TestData.textOption(dataSource, TestData.MAIN, "home", "http://front.test/site2/");
        TestData.textOption(dataSource, TestData.MAIN, "siteurl", "http://front.test/wp/");
        assertThat(probe(http(), ""))
                .isEqualTo("http://front.test/site2|http://front.test/site2/|http://front.test/site2/?p=1"
                        + "|http://front.test/wp|http://front.test/wp/wp-admin/|http://front.test/wp/wp-login.php"
                        + "|http://front.test/site2/wp-json/wp/v2/posts|site2");
    }

    @Test
    void withoutTheOptionsTheSiteRecordIsTheHttpAddress() {
        TestData.dropOption(dataSource, TestData.MAIN, "home");
        TestData.dropOption(dataSource, TestData.MAIN, "siteurl");
        assertThat(probe(http(), ""))
                .isEqualTo("http://localhost|http://localhost/|http://localhost/?p=1|http://localhost"
                        + "|http://localhost/wp-admin/|http://localhost/wp-login.php"
                        + "|http://localhost/wp-json/wp/v2/posts|");
        assertThat(probe(https(), "")).startsWith("https://localhost|https://localhost/|");
    }

    @Test
    void theCacheControlSettingIsUnsetHere() {
        assertThat(probe(http(), "/cache-control")).isEqualTo("-");
    }
}
